package com.barista.service;

import com.barista.model.ExecutionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A long-lived Node.js interpreter per session, serving both JavaScript and
 * TypeScript cells.
 *
 * <p>Before this, JS and TS cells had no cross-cell state at all: each ran in a fresh
 * process and {@code //@ depends} was ignored outright, so the same annotation that
 * ordered a Python or JShell notebook silently did nothing here. That made them the
 * last two languages where the orchestration DSL did not mean what it means
 * everywhere else.
 *
 * <p>State is kept in a persistent {@code vm} context. V8 gives each context its own
 * global lexical environment, so {@code let}, {@code const}, {@code class} and
 * {@code function} declared in one cell are still bound in the next - no rewriting of
 * the user's code, which is what a naive "turn const into var" approach would need.
 *
 * <p>TypeScript is the same kernel with a type-stripping pass in front, using Node's
 * own {@code module.stripTypeScriptTypes}. Type-CHECKING is unchanged and still runs
 * out-of-process in {@link TypeScriptExecutionService}.
 */
@Service
public class NodeKernelService {

    private static final Logger log = LoggerFactory.getLogger(NodeKernelService.class);

    private static final String DONE = "__ARIMA_CELL_DONE__";

    @Value("${barista.data.dir:data}")
    private String dataDir;

    @Value("${barista.node.kernel.timeout-ms:120000}")
    private long timeoutMs;

    /** Keyed by "<sessionId>:<lang>" so a JS cell and a TS cell do not share a heap. */
    private final Map<String, Kernel> kernels = new ConcurrentHashMap<>();

    private static final class Kernel {
        Process process;
        BufferedWriter in;
        BufferedReader out;
        Path driver;
        final Set<String> anchors = new HashSet<>();
        final ReentrantLock lock = new ReentrantLock();
    }

    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param typescript strip TypeScript types before evaluating
     */
    public ExecutionResult execute(String sessionId, String cellId, String code,
                                   boolean typescript) {
        long start = System.currentTimeMillis();
        String lang = typescript ? "ts" : "js";

        if (!isAvailable()) {
            return err(sessionId, cellId,
                    "Node.js was not found on this machine.\n" +
                    "Install Node.js 20+ from https://nodejs.org and restart Arima.", start);
        }

        String anchor = parseAnnotation(code, "anchor");
        List<String> depends = parseList(code, "depends");
        String key = sessionId + ":" + lang;

        Kernel k;
        try {
            k = kernelFor(key, lang);
        } catch (Exception e) {
            return err(sessionId, cellId, "Could not start the Node kernel: " + e.getMessage(), start);
        }

        k.lock.lock();
        try {
            List<String> missing = new ArrayList<>();
            for (String d : depends) if (!k.anchors.contains(d)) missing.add(d);
            if (!missing.isEmpty()) {
                return err(sessionId, cellId,
                        "Missing dependencies: " + String.join(", ", missing) +
                        "\nRun the dependency cells first, or click '-> Run with deps'.", start);
            }

            Frame frame = send(k, stripAnnotations(code), typescript);

            if (frame == null) {
                restart(sessionId);
                return ExecutionResult.stopped(sessionId, cellId, "", "TIMEOUT",
                        "Execution exceeded the time limit and the Node kernel was restarted. "
                        + "Values from earlier cells are gone; re-run them or run the pipeline.",
                        start);
            }

            if (frame.ok && anchor != null && !anchor.isBlank()) k.anchors.add(anchor);

            return ExecutionResult.builder()
                    .sessionId(sessionId).cellId(cellId)
                    .output(frame.output)
                    .error(frame.ok ? "" : frame.error)
                    .status(frame.ok ? "OK" : "RUNTIME_ERROR")
                    .success(frame.ok)
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            restart(sessionId);
            return err(sessionId, cellId, "Node kernel error: " + e.getMessage(), start);
        } finally {
            k.lock.unlock();
        }
    }

    /** Drop both language kernels for a session; the next cell starts clean ones. */
    public void restart(String sessionId) {
        for (String lang : new String[]{"js", "ts"}) {
            Kernel k = kernels.remove(sessionId + ":" + lang);
            if (k == null) continue;
            try { if (k.process != null) k.process.destroyForcibly(); } catch (Exception ignored) { }
            try { if (k.driver != null) Files.deleteIfExists(k.driver); } catch (Exception ignored) { }
            log.info("Node kernel ({}) for session {} stopped", lang, sessionId);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (String key : new ArrayList<>(kernels.keySet())) {
            restart(key.substring(0, key.lastIndexOf(':')));
        }
    }

    // ── Kernel lifecycle ────────────────────────────────────────────────

    private Kernel kernelFor(String key, String lang) throws IOException {
        Kernel existing = kernels.get(key);
        if (existing != null && existing.process != null && existing.process.isAlive()) return existing;
        kernels.remove(key);

        Kernel k = new Kernel();
        k.driver = Files.createTempFile("arima-node-kernel-", ".js");
        Files.writeString(k.driver, DRIVER, StandardCharsets.UTF_8);

        List<String> cmd = new ArrayList<>(List.of("node", "--no-warnings", k.driver.toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Path npmModules = Paths.get(dataDir, "npm-modules", "node_modules").toAbsolutePath();
        pb.environment().put("NODE_PATH", npmModules.toString());
        // The barista helpers are plain JS and valid for both languages, since TypeScript
        // cells are stripped to JS before they reach the context.
        pb.environment().put(JS_PREAMBLE_ENV, NodeJsExecutionService.BARISTA_PREAMBLE);

        k.process = pb.start();
        k.in  = new BufferedWriter(new OutputStreamWriter(k.process.getOutputStream(), StandardCharsets.UTF_8));
        k.out = new BufferedReader(new InputStreamReader(k.process.getInputStream(), StandardCharsets.UTF_8));

        kernels.put(key, k);
        log.info("Node kernel ({}) started for session {} (pid {})", lang, key, k.process.pid());
        return k;
    }

    private record Frame(boolean ok, String output, String error) { }

    private Frame send(Kernel k, String code, boolean typescript) throws IOException {
        k.in.write(b64(code) + " " + (typescript ? "ts" : "js"));
        k.in.write("\n");
        k.in.flush();

        StringBuilder out = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (!k.out.ready()) {
                if (!k.process.isAlive()) break;
                try { TimeUnit.MILLISECONDS.sleep(5); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
                continue;
            }
            String line = k.out.readLine();
            if (line == null) break;
            if (line.startsWith(DONE)) {
                String[] parts = line.split(" ", 3);
                boolean ok = parts.length > 1 && "ok".equals(parts[1]);
                String error = parts.length > 2 && !parts[2].isBlank()
                        ? new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8) : "";
                return new Frame(ok, out.toString(), error);
            }
            out.append(line).append('\n');
        }
        return null;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private ExecutionResult err(String sessionId, String cellId, String message, long start) {
        return ExecutionResult.builder()
                .sessionId(sessionId).cellId(cellId)
                .output("").error(message)
                .status("ERROR").success(false)
                .executionTimeMs(System.currentTimeMillis() - start)
                .build();
    }

    // ── Annotation parsing (identical syntax to every other language) ────

    static String parseAnnotation(String code, String key) {
        for (String line : code.split("\n", 60)) {
            String t = line.strip();
            if (!t.startsWith("//@") && !t.startsWith("#@")) {
                if (t.isEmpty()) continue;
                break;
            }
            String rest = t.substring(t.startsWith("//@") ? 3 : 2).strip();
            if (rest.startsWith(key + ":")) return rest.substring(key.length() + 1).strip();
        }
        return null;
    }

    static List<String> parseList(String code, String key) {
        String v = parseAnnotation(code, key);
        if (v == null || v.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.strip().isEmpty()) out.add(p.strip());
        return out;
    }

    static String stripAnnotations(String code) {
        String[] lines = code.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String t = lines[i].strip();
            if (t.startsWith("//@") || t.startsWith("#@")) { lines[i] = ""; i++; }
            else break;
        }
        return String.join("\n", lines);
    }

    /**
     * The Node side. One persistent vm context holds the session's values; each cell
     * is a separate script run inside it.
     *
     * <p>After the cell's synchronous body finishes, the driver drains the microtask
     * and immediate queues before reporting. The old one-process-per-cell model let
     * Node exit only once the event loop emptied, so a cell that scheduled a
     * .then(...) still printed it; draining preserves that.
     */
    private static final String DRIVER = """
            const vm = require('node:vm');
            const mod = require('node:module');

            const SENTINEL = '__ARIMA_CELL_DONE__';
            // Built rather than written as an escape: this source lives inside a Java
            // text block, where a backslash escape is consumed before Node ever sees it.
            const NL = String.fromCharCode(10);

            function emit(ok, err) {
              const payload = err ? Buffer.from(String(err), 'utf8').toString('base64') : '';
              process.stdout.write(SENTINEL + (ok ? ' ok ' : ' err ') + payload + NL);
            }

            // A cell's synchronous body finishing is not the same as the cell being done.
            // The old one-process-per-cell model let Node exit only once the event loop
            // emptied, so a scheduled .then(...) still printed. Draining preserves that.
            async function drain() {
              for (let i = 0; i < 3; i++) {
                await new Promise(r => setImmediate(r));
              }
            }

            // Cells run in THIS realm rather than a vm.createContext sandbox. A separate
            // context gets its own intrinsics, so an object literal written in a cell is
            // not an instance of the host realm's Object - and an npm package loaded out
            // here then rejects it. mathjs failed exactly that way on
            // math.format(x, { notation: 'engineering' }). One realm, one set of
            // intrinsics, and libraries behave as they do in a plain script.
            //
            // The driver's own bindings are module-scoped, not global, so cell code
            // cannot reach them.
            //
            // Persistence then comes from bindings living on globalThis, which is why
            // top-level declarations are rewritten:
            //   const/let -> var       so a cell can be re-run without
            //                          "Identifier 'x' has already been declared"
            //   class X   -> var X =   a class declaration is lexical and would not survive
            //   export    -> dropped   a cell is not a module; TypeScript cells often
            //                          carry `export const`, which is a syntax error here
            // Only column 0 is touched. Anything indented is inside a function or block,
            // where const, let and class behave exactly as written.
            function toTopLevelGlobals(src) {
              return src.split(NL).map(function (line) {
                if (line.startsWith('export default ')) line = line.slice(15);
                else if (line.startsWith('export ')) line = line.slice(7);

                if (line.startsWith('const ')) return 'var ' + line.slice(6);
                if (line.startsWith('let '))   return 'var ' + line.slice(4);

                if (line.startsWith('class ')) {
                  const rest = line.slice(6);
                  const name = rest.split(/[^A-Za-z0-9_$]/)[0];
                  if (name) return 'var ' + name + ' = class ' + rest;
                }
                return line;
              }).join(NL);
            }

            // require() is module-scoped in CommonJS, so a cell running in the global
            // scope cannot see it. Cells could always require npm packages under the
            // old model, so it is published deliberately.
            globalThis.require = require;

            const PREAMBLE = process.env.ARIMA_JS_PREAMBLE || '';
            if (PREAMBLE) vm.runInThisContext(toTopLevelGlobals(PREAMBLE), { filename: '<arima>' });

            async function runOne(line) {
              const parts = line.split(' ');
              let code = Buffer.from(parts[0], 'base64').toString('utf8');
              const isTs = parts[1] === 'ts';
              try {
                if (isTs) code = mod.stripTypeScriptTypes(code, { mode: 'strip' });
                const result = vm.runInThisContext(toTopLevelGlobals(code),
                                                  { filename: '<cell>' });
                if (result && typeof result.then === 'function') await result;
                await drain();
                emit(true);
              } catch (e) {
                await drain();
                emit(false, (e && e.stack) ? e.stack : String(e));
              }
            }

            // Cells are queued so a slow one cannot interleave with the next.
            let chain = Promise.resolve();
            let buffer = '';
            process.stdin.setEncoding('utf8');
            process.stdin.on('data', function (chunk) {
              buffer += chunk;
              let nl;
              while ((nl = buffer.indexOf(NL)) >= 0) {
                const line = buffer.slice(0, nl).trim();
                buffer = buffer.slice(nl + 1);
                if (line) chain = chain.then(() => runOne(line)).catch(() => {});
              }
            });
            """;

    /** The JS barista helpers, evaluated once into each kernel's context. */
    static final String JS_PREAMBLE_ENV = "ARIMA_JS_PREAMBLE";
}
