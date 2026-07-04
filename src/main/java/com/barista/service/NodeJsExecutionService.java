package com.barista.service;

import com.barista.model.ExecutionResult;
import com.barista.util.VariableInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript execution service using Node.js.
 *
 * Each cell runs in a fresh Node.js subprocess — no shared state between cells
 * (same model as JavaCompilerService). Installed npm packages are available via
 * NODE_PATH so users can call require('package-name') directly.
 *
 * Prerequisites: Node.js must be installed and `node` must be on the PATH.
 *
 * Arima JS helpers pre-injected into every cell:
 *   barista.table(data)     — pretty-print an array of objects as a text table
 *   barista.display(obj)    — JSON.stringify with indent
 *   barista.stats(arr)      — basic stats summary (count, min, max, mean)
 */
@Service
public class NodeJsExecutionService {

    private static final Logger log = LoggerFactory.getLogger(NodeJsExecutionService.class);
    private static final int TIMEOUT_SECONDS = 30;

    // Preamble injected at the top of every JS cell
    private static final String BARISTA_PREAMBLE =
        "const barista = {\n" +
        "  table(data) {\n" +
        "    if (!Array.isArray(data) || data.length === 0) { console.log('(empty)'); return; }\n" +
        "    const keys = Object.keys(data[0]);\n" +
        "    const widths = keys.map(k => Math.max(k.length, ...data.map(r => String(r[k] ?? '').length)));\n" +
        "    const sep = '+' + widths.map(w => '-'.repeat(w + 2)).join('+') + '+';\n" +
        "    const row = r => '|' + keys.map((k,i) => ' ' + String(r[k] ?? '').padEnd(widths[i]) + ' ').join('|') + '|';\n" +
        "    console.log(sep);\n" +
        "    console.log('|' + keys.map((k,i) => ' ' + k.padEnd(widths[i]) + ' ').join('|') + '|');\n" +
        "    console.log(sep);\n" +
        "    data.forEach(r => console.log(row(r)));\n" +
        "    console.log(sep);\n" +
        "    console.log(`${data.length} row(s)`);\n" +
        "  },\n" +
        "  display(obj) { console.log(JSON.stringify(obj, null, 2)); },\n" +
        "  html(content) { console.log('BARISTA_HTML:' + String(content).replace(/\\n\\s*/g, ' ')); },\n" +
        "  stats(arr) {\n" +
        "    if (!arr || arr.length === 0) { console.log('(empty array)'); return; }\n" +
        "    const n = arr.length;\n" +
        "    const sorted = [...arr].sort((a,b) => a-b);\n" +
        "    const sum = arr.reduce((a,b) => a+b, 0);\n" +
        "    const mean = sum / n;\n" +
        "    const variance = arr.reduce((s,x) => s + (x-mean)**2, 0) / n;\n" +
        "    console.log(`count: ${n}  min: ${sorted[0]}  max: ${sorted[n-1]}  mean: ${mean.toFixed(4)}  std: ${Math.sqrt(variance).toFixed(4)}`);\n" +
        "  }\n" +
        "};\n\n";

    private static final int PREAMBLE_LINE_COUNT = BARISTA_PREAMBLE.split("\n", -1).length - 1;

    @Value("${barista.data.dir:data}")
    private String dataDir;

    private final AtomicInteger execCounter = new AtomicInteger(0);
    private final InteractiveProcessRunner runner;

    public NodeJsExecutionService(InteractiveProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Execute JavaScript code using Node.js.
     *
     * @param sessionId  Notebook session (metadata only)
     * @param cellId     Cell that triggered this execution
     * @param code       JavaScript source code
     */
    public ExecutionResult execute(String sessionId, String cellId, String code) {
        long start = System.currentTimeMillis();

        // Check node is available
        if (!isNodeAvailable()) {
            return err(sessionId, cellId,
                "Node.js not found. Install Node.js from https://nodejs.org/ " +
                "and ensure 'node' is on your PATH.", start);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("barista-js-");
            Path scriptFile = tempDir.resolve("script.js");

            // Append a small trailer that emits all top-level declarations
            // back through stdout (see VariableInspector). The trailer runs
            // only if the user's code completes — failures still produce a
            // valid result, just without a populated variables panel.
            List<String> jsNames = VariableInspector.parseJsDeclarations(code);
            String trailer = VariableInspector.buildJsTrailer(jsNames);

            // Write preamble + user code + trailer
            Files.writeString(scriptFile, BARISTA_PREAMBLE + code + trailer);

            // Resolve npm modules path
            Path npmModules = Paths.get(dataDir, "npm-modules", "node_modules").toAbsolutePath();

            ProcessBuilder pb = new ProcessBuilder("node", "--no-warnings", scriptFile.toString());

            // Set NODE_PATH so require('package') finds installed npm modules
            pb.environment().put("NODE_PATH", npmModules.toString());

            // Interactive stdin (when a cell is run in the UI) + runaway guards live in the
            // shared runner; non-interactive callers (pipelines/MCP) get plain batch capture.
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

            // Clean up temp file path from Node.js error messages so users see clean errors
            String cleanErr = errStr.replace(scriptFile.toString(), "script.js")
                                    .replace(tempDir.toString(), "");

            // Adjust line numbers: subtract BARISTA_PREAMBLE line count from error messages
            cleanErr = shiftLineNumbers(cleanErr, PREAMBLE_LINE_COUNT);

            boolean success = exitCode == 0 && errStr.isEmpty();

            // Strip the variable-dump sentinel block out of stdout so users
            // don't see the marker noise — and attach the parsed variables.
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

        } catch (Exception e) {
            log.error("Node.js execution error in cell {}", cellId, e);
            return err(sessionId, cellId,
                e.getClass().getSimpleName() + ": " + e.getMessage(), start);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    /**
     * Adjust line numbers in Node.js error messages to account for injected preamble lines.
     * Node errors look like: script.js:25  or  at Object.<anonymous> (script.js:25:5)
     */
    private String shiftLineNumbers(String error, int offset) {
        if (error == null || error.isEmpty()) return error;
        Pattern pattern = Pattern.compile("script\\.js:(\\d+)");
        Matcher matcher = pattern.matcher(error);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            int lineNo = Integer.parseInt(matcher.group(1));
            int adjusted = Math.max(1, lineNo - offset);
            matcher.appendReplacement(result, "script.js:" + adjusted);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean isNodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
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
