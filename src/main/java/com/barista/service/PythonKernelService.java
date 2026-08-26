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
 * A long-lived Python interpreter per session, so cells share state the way JShell
 * cells already do.
 *
 * <p>The previous model ran every cell in a fresh process and replayed each
 * ancestor's SOURCE to rebuild the state it needed. That is reproducible, but it
 * replays derivation rather than sharing state, and three things fall out of it:
 * an expensive ancestor is re-paid in full by every dependent cell; anything
 * non-deterministic (uuid, random, now()) silently differs between the cell that
 * printed it and the cells that depend on it; and any side effect in an ancestor -
 * a file written, a row inserted, a request sent - happens again for every
 * dependent.
 *
 * <p>{@code //@ depends} keeps exactly the meaning it has everywhere else in Arima:
 * the ancestor must have run in this session before the dependent can. Nothing about
 * the DSL changes - only how Python satisfies it.
 *
 * <p>Reproducibility is not given up: restarting the kernel clears the session, and
 * running a pipeline restarts first, so one click still proves the notebook rebuilds
 * from nothing.
 */
@Service
public class PythonKernelService {

    private static final Logger log = LoggerFactory.getLogger(PythonKernelService.class);

    /** Emitted by the driver after each cell; must not occur in ordinary output. */
    private static final String DONE = "__ARIMA_CELL_DONE__";

    private final PythonExecutionService python;

    @Value("${barista.data.dir:data}")
    private String dataDir;

    @Value("${barista.python.kernel.timeout-ms:120000}")
    private long timeoutMs;

    private final Map<String, Kernel> kernels = new ConcurrentHashMap<>();

    public PythonKernelService(PythonExecutionService python) {
        this.python = python;
    }

    /** One interpreter, plus the anchors that have successfully run inside it. */
    private static final class Kernel {
        Process process;
        BufferedWriter in;
        BufferedReader out;
        Path driver;
        Path preamble;
        final Set<String> anchors = new HashSet<>();
        final ReentrantLock lock = new ReentrantLock();
    }

    public boolean isAvailable() {
        return python.isPythonAvailable();
    }

    public ExecutionResult execute(String sessionId, String cellId, String code, String stdin) {
        long start = System.currentTimeMillis();

        if (!python.isPythonAvailable()) {
            return err(sessionId, cellId,
                    "Python was not found on this machine.\n" +
                    "Install Python 3.8+ from https://www.python.org/downloads/ (tick 'Add to PATH'),\n" +
                    "then restart Arima.", start);
        }

        String anchor = parseAnnotation(code, "anchor");
        List<String> depends = parseList(code, "depends");

        Kernel k;
        try {
            k = kernelFor(sessionId);
        } catch (Exception e) {
            return err(sessionId, cellId, "Could not start the Python kernel: " + e.getMessage(), start);
        }

        k.lock.lock();
        try {
            // Same contract as every other language: an ancestor must have run first.
            List<String> missing = new ArrayList<>();
            for (String d : depends) if (!k.anchors.contains(d)) missing.add(d);
            if (!missing.isEmpty()) {
                return err(sessionId, cellId,
                        "Missing dependencies: " + String.join(", ", missing) +
                        "\nRun the dependency cells first, or click '-> Run with deps'.", start);
            }

            Frame frame = send(k, code, stdin);

            if (frame == null) {
                // No sentinel came back: the cell is wedged or the interpreter died.
                // Killing it loses the session's state, which is worth saying plainly.
                restart(sessionId);
                return ExecutionResult.stopped(sessionId, cellId, "", "TIMEOUT",
                        "Execution exceeded the time limit and the Python kernel was restarted. "
                        + "Variables from earlier cells are gone; re-run them or run the pipeline.",
                        start);
            }

            if (frame.ok && anchor != null && !anchor.isBlank()) k.anchors.add(anchor);

            long elapsed = System.currentTimeMillis() - start;
            return ExecutionResult.builder()
                    .sessionId(sessionId).cellId(cellId)
                    .output(frame.output)
                    .error(frame.ok ? "" : frame.error)
                    .status(frame.ok ? "OK" : "RUNTIME_ERROR")
                    .success(frame.ok)
                    .executionTimeMs(elapsed)
                    .build();
        } catch (Exception e) {
            restart(sessionId);
            return err(sessionId, cellId, "Python kernel error: " + e.getMessage(), start);
        } finally {
            k.lock.unlock();
        }
    }

    /** Drop the session's interpreter; the next cell starts a clean one. */
    public void restart(String sessionId) {
        Kernel k = kernels.remove(sessionId);
        if (k == null) return;
        try { if (k.process != null) k.process.destroyForcibly(); } catch (Exception ignored) { }
        try { if (k.driver != null) Files.deleteIfExists(k.driver); } catch (Exception ignored) { }
        try { if (k.preamble != null) Files.deleteIfExists(k.preamble); } catch (Exception ignored) { }
        log.info("Python kernel for session {} stopped", sessionId);
    }

    /** Anchors that have run in this session - used to report pipeline state. */
    public Set<String> anchors(String sessionId) {
        Kernel k = kernels.get(sessionId);
        return k == null ? Set.of() : Set.copyOf(k.anchors);
    }

    @PreDestroy
    public void shutdown() {
        for (String s : new ArrayList<>(kernels.keySet())) restart(s);
    }

    // ── Kernel lifecycle ────────────────────────────────────────────────

    private Kernel kernelFor(String sessionId) throws IOException {
        Kernel existing = kernels.get(sessionId);
        if (existing != null && existing.process != null && existing.process.isAlive()) return existing;
        kernels.remove(sessionId);

        Kernel k = new Kernel();
        k.driver = Files.createTempFile("arima-kernel-", ".py");
        Files.writeString(k.driver, DRIVER, StandardCharsets.UTF_8);

        // The barista helpers go in their own file and are exec'd into the cell
        // namespace, so the driver never has to embed them as a nested literal.
        k.preamble = Files.createTempFile("arima-preamble-", ".py");
        Files.writeString(k.preamble, PythonExecutionService.BARISTA_PREAMBLE, StandardCharsets.UTF_8);

        List<String> cmd = new ArrayList<>(python.pythonCommand());
        cmd.add("-u");
        cmd.add("-X"); cmd.add("utf8");
        cmd.add(k.driver.toString());
        cmd.add(k.preamble.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);   // one stream to read, so no reader can deadlock

        Path site = Paths.get(dataDir, "pypi-packages", "site").toAbsolutePath();
        String sep = System.getProperty("os.name", "").toLowerCase().contains("win") ? ";" : ":";
        String existingPath = pb.environment().getOrDefault("PYTHONPATH", "");
        pb.environment().put("PYTHONPATH",
                existingPath.isBlank() ? site.toString() : site + sep + existingPath);
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        k.process = pb.start();
        k.in  = new BufferedWriter(new OutputStreamWriter(k.process.getOutputStream(), StandardCharsets.UTF_8));
        k.out = new BufferedReader(new InputStreamReader(k.process.getInputStream(), StandardCharsets.UTF_8));

        kernels.put(sessionId, k);
        log.info("Python kernel started for session {} (pid {})", sessionId, k.process.pid());
        return k;
    }

    private record Frame(boolean ok, String output, String error) { }

    /**
     * Send one cell and read until the driver's sentinel. Code and stdin are
     * base64-encoded so a newline or a stray sentinel-looking line inside user code
     * cannot corrupt the framing.
     */
    private Frame send(Kernel k, String code, String stdin) throws IOException {
        String payload = b64(stripAnnotations(code)) + " " + b64(stdin == null ? "" : stdin);
        k.in.write(payload);
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
                String error = parts.length > 2 ? new String(
                        Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8) : "";
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

    /** Drop the leading //@ annotation block so line numbers in tracebacks line up. */
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
     * The interpreter side. Reads "codeB64 stdinB64" per line, executes into one
     * persistent namespace, and terminates each cell with the sentinel.
     *
     * <p>The cell's Stdin panel becomes an in-memory stream for the duration of the
     * cell, so input() still works even though the real stdin carries the protocol.
     */
    /**
     * The interpreter side. Reads "codeB64 stdinB64" per line, executes into one
     * persistent namespace, and terminates each cell with the sentinel.
     *
     * <p>The Arima helper preamble is written to its own file and exec'd INTO that
     * namespace, so `barista` is available to every cell without embedding the
     * preamble inside this string.
     *
     * <p>The cell's Stdin panel becomes an in-memory stream for the duration of the
     * cell, so input() still works even though the real stdin carries the protocol.
     */
    private static final String DRIVER = """
            import sys, io, base64, traceback

            SENTINEL = "__ARIMA_CELL_DONE__"
            NS = {"__name__": "__main__"}

            with io.open(sys.argv[1], encoding="utf-8") as fh:
                exec(compile(fh.read(), "<arima>", "exec"), NS)

            real_stdin = sys.stdin

            def emit(ok, err=""):
                payload = base64.b64encode(err.encode("utf-8")).decode("ascii")
                # print() rather than stdout.write: a newline escape inside this
                # Java text block would be turned into a real newline and break
                # the Python string literal.
                print(SENTINEL, "ok" if ok else "err", payload, flush=True)

            while True:
                line = real_stdin.readline()
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                parts = line.split(" ")
                code = base64.b64decode(parts[0]).decode("utf-8")
                feed = base64.b64decode(parts[1]).decode("utf-8") if len(parts) > 1 else ""

                sys.stdin = io.StringIO(feed)
                try:
                    exec(compile(code, "<cell>", "exec"), NS)
                    sys.stdout.flush()
                    emit(True)
                except SystemExit:
                    sys.stdout.flush()
                    emit(True)
                except BaseException:
                    sys.stdout.flush()
                    emit(False, traceback.format_exc())
                finally:
                    sys.stdin = real_stdin
            """;
}
