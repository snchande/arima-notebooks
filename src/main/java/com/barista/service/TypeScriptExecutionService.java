package com.barista.service;

import com.barista.model.ExecutionResult;
import com.barista.util.VariableInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TypeScript execution service.
 *
 * Each cell runs in a fresh Node.js subprocess (per-cell isolation, same model
 * as {@link NodeJsExecutionService}).  Installed npm packages are available via
 * NODE_PATH so users can {@code import * as ss from "simple-statistics"} or
 * {@code require('package-name')} directly.
 *
 * <h3>Execution path</h3>
 * <ol>
 *   <li>Write a {@code script.ts} containing the typed Arima preamble + user code.</li>
 *   <li>If {@code tsc} is available, run {@code tsc --noEmit} to surface type errors
 *       (line numbers are shifted back to user-source space).</li>
 *   <li>Run the script via {@code node --experimental-strip-types script.ts}.
 *       Node 22.6+ supports this flag natively (Node 23.6+ defaults it on, and
 *       Node 24+ runs {@code .ts} files directly).</li>
 * </ol>
 *
 * Prerequisites: Node.js ≥ 22.6 on the PATH.
 *
 * Arima TS helpers pre-injected into every cell (typed):
 * <pre>
 *   barista.table(rows)     // ASCII table from an array of objects
 *   barista.display(value)  // JSON.stringify with indent
 *   barista.html(content)   // Inline HTML output (rendered in the cell)
 *   barista.stats(numbers)  // count / min / max / mean / std
 * </pre>
 */
@Service
public class TypeScriptExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TypeScriptExecutionService.class);
    private static final int TIMEOUT_SECONDS = 30;

    // Typed Arima preamble injected at the top of every TS cell.
    // Kept self-contained so it compiles standalone under "noImplicitAny: false".
    private static final String BARISTA_PREAMBLE =
        "// ── Arima TypeScript preamble (auto-injected) ──\n" +
        "type BaristaRow = Record<string, unknown>;\n" +
        "const barista = {\n" +
        "  table(data: BaristaRow[]): void {\n" +
        "    if (!Array.isArray(data) || data.length === 0) { console.log('(empty)'); return; }\n" +
        "    const keys = Object.keys(data[0]);\n" +
        "    const widths = keys.map(k => Math.max(k.length, ...data.map(r => String((r as any)[k] ?? '').length)));\n" +
        "    const sep = '+' + widths.map(w => '-'.repeat(w + 2)).join('+') + '+';\n" +
        "    const row = (r: BaristaRow) => '|' + keys.map((k,i) => ' ' + String((r as any)[k] ?? '').padEnd(widths[i]) + ' ').join('|') + '|';\n" +
        "    console.log(sep);\n" +
        "    console.log('|' + keys.map((k,i) => ' ' + k.padEnd(widths[i]) + ' ').join('|') + '|');\n" +
        "    console.log(sep);\n" +
        "    data.forEach(r => console.log(row(r)));\n" +
        "    console.log(sep);\n" +
        "    console.log(`${data.length} row(s)`);\n" +
        "  },\n" +
        "  display(obj: unknown): void { console.log(JSON.stringify(obj, null, 2)); },\n" +
        "  html(content: string): void { console.log('BARISTA_HTML:' + String(content).replace(/\\n\\s*/g, ' ')); },\n" +
        "  stats(arr: number[]): void {\n" +
        "    if (!arr || arr.length === 0) { console.log('(empty array)'); return; }\n" +
        "    const n = arr.length;\n" +
        "    const sorted = [...arr].sort((a,b) => a-b);\n" +
        "    const sum = arr.reduce((a,b) => a+b, 0);\n" +
        "    const mean = sum / n;\n" +
        "    const variance = arr.reduce((s,x) => s + (x-mean)**2, 0) / n;\n" +
        "    console.log(`count: ${n}  min: ${sorted[0]}  max: ${sorted[n-1]}  mean: ${mean.toFixed(4)}  std: ${Math.sqrt(variance).toFixed(4)}`);\n" +
        "  }\n" +
        "};\n" +
        "// ── end Arima preamble ──\n\n";

    private static final int PREAMBLE_LINE_COUNT = BARISTA_PREAMBLE.split("\n", -1).length - 1;

    @Value("${barista.data.dir:data}")
    private String dataDir;

    private final AtomicInteger execCounter = new AtomicInteger(0);
    private final InteractiveProcessRunner runner;

    public TypeScriptExecutionService(InteractiveProcessRunner runner) {
        this.runner = runner;
    }

    /** Cached availability — checked once per JVM run. */
    private volatile Boolean nodeAvailable = null;
    private volatile Boolean tscAvailable  = null;
    private volatile String  nodeVersion   = null;
    private volatile String  tscVersion    = null;

    /**
     * Execute TypeScript code via Node.js's built-in type-stripping runtime.
     *
     * @param sessionId  Notebook session (metadata only — TS cells are per-cell isolated)
     * @param cellId     Cell that triggered this execution
     * @param code       TypeScript source code
     */
    public ExecutionResult execute(String sessionId, String cellId, String code) {
        long start = System.currentTimeMillis();

        if (!isNodeAvailable()) {
            return err(sessionId, cellId,
                "Node.js not found. Install Node.js 22.6+ from https://nodejs.org/ " +
                "and ensure 'node' is on your PATH. " +
                "TypeScript cells use Node's built-in type-stripping runtime — no extra installer needed.",
                start);
        }
        if (!isNodeVersionSupported()) {
            return err(sessionId, cellId,
                "Node.js " + (nodeVersion != null ? nodeVersion : "unknown") +
                " is too old for TypeScript cells. Upgrade to Node.js 22.6+ " +
                "(recommended: 24 LTS) to enable type-stripping execution.",
                start);
        }

        Path tempDir = null;
        try {
            // We place the temp script INSIDE data/npm-modules/ (alongside node_modules)
            // so Node's ESM resolver finds installed packages without needing NODE_PATH.
            // (NODE_PATH only works for CommonJS require(); ESM `import` walks up the
            // script's directory tree looking for node_modules.)
            // If data/npm-modules/ doesn't exist yet, fall back to the system temp dir.
            Path npmRoot = Paths.get(dataDir, "npm-modules").toAbsolutePath();
            if (Files.isDirectory(npmRoot)) {
                Files.createDirectories(npmRoot);
                tempDir = Files.createTempDirectory(npmRoot, ".barista-ts-");
            } else {
                tempDir = Files.createTempDirectory("barista-ts-");
            }
            Path scriptFile = tempDir.resolve("script.ts");

            // Append a variable-dump trailer so the runtime emits its
            // top-level let/const/function/class state via stdout sentinels.
            Map<String, String> tsDecls = VariableInspector.parseTsDeclarations(code);
            String trailer = VariableInspector.buildTsTrailer(tsDecls);

            Files.writeString(scriptFile, BARISTA_PREAMBLE + code + trailer);

            // Optional type-check via tsc --noEmit (only if available).
            // We deliberately keep this best-effort: a tsc misconfiguration must
            // never prevent a runnable cell from running, so any failure here is
            // surfaced via the `error` channel but execution still proceeds.
            StringBuilder typeErrors = new StringBuilder();
            if (isTscAvailable()) {
                String tscOut = runTypeCheck(scriptFile, tempDir);
                if (tscOut != null && !tscOut.isBlank()) {
                    typeErrors.append(shiftLineNumbers(tscOut, PREAMBLE_LINE_COUNT));
                }
            }

            ExecutionResult runResult = runScript(sessionId, cellId, tempDir, scriptFile, start);

            // If type-check produced diagnostics, fold them into the error stream
            // (regardless of whether the runtime succeeded) — the user wants both.
            if (typeErrors.length() > 0) {
                String combined = runResult.getError() == null || runResult.getError().isBlank()
                        ? typeErrors.toString().trim()
                        : (typeErrors.toString().trim() + "\n" + runResult.getError());
                boolean success = runResult.isSuccess() && typeErrors.length() == 0;
                return ExecutionResult.builder()
                        .sessionId(sessionId).cellId(cellId)
                        .output(runResult.getOutput())
                        .error(combined)
                        .status(success ? "OK" : "TYPE_ERROR")
                        .success(success)
                        .executionTimeMs(runResult.getExecutionTimeMs())
                        .executionCount(runResult.getExecutionCount())
                        .localVariables(runResult.getLocalVariables())
                        .build();
            }
            return runResult;
        } catch (Exception e) {
            log.error("TypeScript execution error in cell {}", cellId, e);
            return err(sessionId, cellId,
                e.getClass().getSimpleName() + ": " + e.getMessage(), start);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    /** Run the user script via node + experimental-strip-types. */
    private ExecutionResult runScript(String sessionId, String cellId,
                                       Path tempDir, Path scriptFile, long start) throws Exception {
        Path npmModules = Paths.get(dataDir, "npm-modules", "node_modules").toAbsolutePath();

        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        cmd.add("--no-warnings");
        // Node 23.6+ enables type-stripping by default. Older versions need the flag.
        // Passing it on newer Nodes is harmless. On Node 24+ the flag is a silent no-op.
        cmd.add("--experimental-strip-types");
        cmd.add(scriptFile.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("NODE_PATH", npmModules.toString());

        // Interactive stdin (UI runs) + runaway guards via the shared runner.
        InteractiveProcessRunner.ProcRun run = runner.run(pb);

        if (run.truncated()) {
            return ExecutionResult.stopped(sessionId, cellId, run.stdout(), "OUTPUT_LIMIT",
                "Output exceeded the line limit and was stopped (possible runaway loop).", start);
        }
        if (run.timedOut()) {
            return ExecutionResult.stopped(sessionId, cellId, run.stdout(), "TIMEOUT",
                "Execution exceeded the time limit and was stopped (possible never-ending loop).", start);
        }

        long elapsed  = System.currentTimeMillis() - start;
        int  exitCode = run.exitCode();
        String errStr = run.stderr().trim();

        // Clean up the temp path from Node's diagnostics so users see "script.ts" instead of "/tmp/xyz/script.ts".
        String cleanErr = errStr.replace(scriptFile.toString(), "script.ts")
                                .replace(tempDir.toString(), "");
        cleanErr = shiftLineNumbers(cleanErr, PREAMBLE_LINE_COUNT);

        boolean success = exitCode == 0 && errStr.isEmpty();

        // Pull the variable dump out of stdout (if the trailer ran).
        VariableInspector.ParsedOutput parsed =
                VariableInspector.parseDumpFromOutput(run.stdout());

        return ExecutionResult.builder()
                .sessionId(sessionId).cellId(cellId)
                .output(parsed.cleanedStdout)
                .error(cleanErr)
                .status(success ? "OK" : "RUNTIME_ERROR")
                .success(success)
                .executionTimeMs(elapsed)
                .executionCount(execCounter.incrementAndGet())
                .localVariables(parsed.variables)
                .build();
    }

    /**
     * Run `tsc --noEmit` against the temp file with relaxed settings —
     * surfacing type errors without forcing users to author a tsconfig.
     * Returns the diagnostics text (file paths cleaned), or null on failure.
     */
    private String runTypeCheck(Path scriptFile, Path tempDir) {
        try {
            List<String> cmd = new ArrayList<>();
            // Use a shell-tolerant invocation so Windows .cmd shims also resolve.
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (isWindows) { cmd.add("cmd"); cmd.add("/c"); }
            cmd.add("tsc");
            cmd.add("--noEmit");
            cmd.add("--target"); cmd.add("es2022");
            cmd.add("--module"); cmd.add("nodenext");
            cmd.add("--moduleResolution"); cmd.add("nodenext");
            cmd.add("--allowJs");
            cmd.add("--esModuleInterop");
            cmd.add("--skipLibCheck");
            cmd.add("--strict");
            cmd.add("--noImplicitAny"); cmd.add("false");
            cmd.add(scriptFile.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            Thread t = captureStream(p.getInputStream(), out);
            t.start();
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            t.join(1000);
            if (!finished) { p.destroyForcibly(); return null; }
            if (p.exitValue() == 0) return null; // no type errors
            // Strip path prefixes before "script.ts" — tsc may emit relative paths.
            // Filter out TS2307 "Cannot find module" — many npm packages ship
            // without .d.ts files, and a missing type declaration shouldn't
            // block execution that would otherwise succeed at runtime.
            String cleaned = out.toString()
                .replaceAll("(?m)^[^\\s(]*?script\\.ts", "script.ts");
            // Skip false-positives that arise because we don't ship @types/node:
            //   TS2307 — "Cannot find module 'foo'" (npm packages without .d.ts)
            //   TS2591 — "Cannot find name 'process'/'require'/'__dirname' …"
            //   TS2580 — "Cannot find name 'process'. Do you need to install type definitions for node?"
            // These complaints are about types, not runtime — Node ships those names
            // natively and our --strip-types runtime resolves them just fine.
            StringBuilder kept = new StringBuilder();
            boolean skipContinuation = false;
            for (String line : cleaned.split("\n", -1)) {
                if (line.matches("^script\\.ts\\(\\d+,\\d+\\): error TS(2307|2591|2580)\\b.*")) {
                    skipContinuation = true;
                    continue;
                }
                // tsc multi-line messages start at column 0 with the file path;
                // continuation lines are indented. Skip indented lines after a filtered error.
                if (skipContinuation && line.startsWith(" ")) continue;
                skipContinuation = false;
                kept.append(line).append("\n");
            }
            String result = kept.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.debug("tsc invocation failed (non-fatal): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Adjust line numbers in Node / tsc diagnostics so they map back to the
     * user-authored source (subtract preamble line count).
     * Matches both Node's "script.ts:25" and tsc's "script.ts(25,7):".
     */
    private String shiftLineNumbers(String error, int offset) {
        if (error == null || error.isEmpty()) return error;
        Pattern[] patterns = new Pattern[] {
            Pattern.compile("script\\.ts:(\\d+)"),
            Pattern.compile("script\\.ts\\((\\d+),(\\d+)\\)")
        };
        String result = error;
        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int lineNo = Integer.parseInt(m.group(1));
                int adjusted = Math.max(1, lineNo - offset);
                String replacement = m.group().replace(m.group(1), String.valueOf(adjusted));
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }

    /** Is `node` reachable on the PATH? Result is cached. */
    public boolean isNodeAvailable() {
        if (nodeAvailable != null) return nodeAvailable;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) nodeVersion = out;
            nodeAvailable = ok;
        } catch (Exception e) {
            nodeAvailable = false;
        }
        return nodeAvailable;
    }

    /** Detect Node ≥ 22.6 — the minimum that supports --experimental-strip-types. */
    public boolean isNodeVersionSupported() {
        if (!isNodeAvailable()) return false;
        if (nodeVersion == null) return false;
        Matcher m = Pattern.compile("v?(\\d+)\\.(\\d+)").matcher(nodeVersion);
        if (!m.find()) return false;
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        return major > 22 || (major == 22 && minor >= 6);
    }

    /** Is `tsc` available for optional type-checking? Result is cached. */
    public boolean isTscAvailable() {
        if (tscAvailable != null) return tscAvailable;
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            List<String> cmd = isWindows
                    ? List.of("cmd", "/c", "tsc", "--version")
                    : List.of("tsc", "--version");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) tscVersion = out;
            tscAvailable = ok;
        } catch (Exception e) {
            tscAvailable = false;
        }
        return tscAvailable;
    }

    /** Human-readable detail string used by the /status endpoint. */
    public String getStatusDetail() {
        if (!isNodeAvailable()) {
            return "Node.js not found — install Node 22.6+ to enable TypeScript cells";
        }
        if (!isNodeVersionSupported()) {
            return "Node " + nodeVersion + " is too old — TypeScript needs Node 22.6+";
        }
        String base = "Node " + nodeVersion + " (built-in TS strip)";
        if (isTscAvailable()) base += " + tsc " + tscVersion + " (type-check)";
        else base += " — install `typescript` globally for type-checking";
        return base;
    }

    public boolean isAvailable() {
        return isNodeAvailable() && isNodeVersionSupported();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Thread captureStream(InputStream is, StringBuilder target) {
        return new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    target.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        });
    }

    private ExecutionResult err(String sessionId, String cellId, String error, long start) {
        return ExecutionResult.builder()
                .sessionId(sessionId).cellId(cellId)
                .output("").error(error)
                .status("ERROR").success(false)
                .executionTimeMs(System.currentTimeMillis() - start)
                .executionCount(0)
                .build();
    }

    private void deleteTempDir(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {}
    }
}
