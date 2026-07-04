package com.barista.shell;

import com.barista.model.ExecutionResult;
import jdk.jshell.Diag;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.VarSnippet;

import com.barista.util.BaristaInput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a single JShell instance providing isolated execution per notebook.
 *
 * ROOT CAUSE: JShell by default uses a remote subprocess (RemoteExecutionControl).
 * System.setOut() in our JVM does NOT affect the subprocess. The correct fix is to
 * register a custom OutputStream with JShell.builder().out()/.err() that acts as a
 * proxy, routing all snippet stdout/stderr into a ByteArrayOutputStream which we
 * reset before each eval() call.
 *
 * Thread safety: execute() is synchronized per ShellSession instance.
 */
public class ShellSession {

    private final String sessionId;
    private JShell jshell;
    private final List<String> classpath;
    private final AtomicInteger executionCounter;

    /** Captures output from JShell's remote execution subprocess. */
    private final ByteArrayOutputStream captureBuffer = new ByteArrayOutputStream(4096);

    /** Proxy stream registered with JShell builder — routes snippet output to captureBuffer. */
    private PrintStream proxyStream;

    /** Runs each cell's eval on a dedicated thread so it can be time-boxed and stopped. */
    private final ExecutorService evalExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jshell-eval");
        t.setDaemon(true);
        return t;
    });

    /** Set when the current cell should stop (manual Stop button / interrupt). */
    private volatile boolean stopRequested = false;

    /** Live count of newlines written to the capture buffer — drives the output-line cap. */
    private final AtomicInteger outputLineCount = new AtomicInteger(0);

    public ShellSession(String sessionId) {
        this.sessionId = sessionId;
        this.classpath = new ArrayList<>();
        this.executionCounter = new AtomicInteger(0);
        initJShell();
    }

    private void initJShell() {
        // Create a proxy OutputStream that writes into captureBuffer.
        // We register this with JShell so that System.out / System.err from
        // evaluated snippets are routed here (even in remote subprocess mode).
        OutputStream proxy = new OutputStream() {
            @Override public void write(int b) {
                captureBuffer.write(b);
                if (b == '\n') outputLineCount.incrementAndGet();
            }
            @Override public void write(byte[] b, int off, int len) {
                captureBuffer.write(b, off, len);
                for (int i = off; i < off + len; i++) if (b[i] == '\n') outputLineCount.incrementAndGet();
            }
            @Override public void flush() throws IOException            { captureBuffer.flush(); }
        };

        try {
            proxyStream = new PrintStream(proxy, true, "UTF-8");
        } catch (Exception e) {
            proxyStream = new PrintStream(proxy, true);
        }

        jshell = JShell.builder()
                .out(proxyStream)
                .err(proxyStream)
                .in(BaristaInput.STDIN)
                .build();

        // ── Make the server's dependency JARs available to JShell snippets ──
        // JShell has its own classloader and does NOT automatically inherit the
        // server's runtime classpath.  We seed it here from two sources:
        //
        //   1. java.class.path — populated by mvn spring-boot:run and direct java -jar
        //      invocations; contains individual JAR paths and target/classes.
        //   2. URLClassLoader chain — catches additional entries registered by
        //      Spring Boot's LaunchedURLClassLoader in some packaging modes.
        addServerClasspathToJShell();

        // Explicitly seed common imports so they are guaranteed in scope
        // regardless of JDK version startup defaults.
        String[] startupImports = {
            "import java.util.*;",
            "import java.util.stream.*;",
            "import java.util.function.*;",
            "import java.util.concurrent.*;",
            "import java.util.regex.*;",
            "import java.io.*;",
            "import java.math.*;",
            "import java.net.*;",
            "import java.nio.file.*;",
            "import java.time.*;",
            "import java.time.format.*;",
            "import java.time.temporal.*;",
            // Data science & charting (always available — no install needed)
            "import com.barista.util.BaristaDisplay;",
            "import org.knowm.xchart.*;",
            "import org.knowm.xchart.style.*;",
            "import org.knowm.xchart.style.markers.*;",
            "import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;",
            "import org.apache.commons.math3.stat.regression.SimpleRegression;",
            "import org.apache.commons.math3.distribution.*;",
            // Tablesaw DataFrame
            "import tech.tablesaw.api.*;",
            "import tech.tablesaw.api.Table;",
            "import tech.tablesaw.selection.Selection;",
        };
        for (String imp : startupImports) {
            jshell.eval(imp);
        }

        for (String jar : classpath) {
            jshell.addToClasspath(jar);
        }

        // BaristaInput.STDIN is wired directly into JShell via builder().in() above.
        // No jshell.eval needed — the builder routes it through JShell's execution engine
        // so Scanner / BufferedReader etc. read from the cell's Stdin panel values.
    }

    /**
     * Seed JShell with every JAR / directory the server itself was started with.
     * This makes XChart, Commons Math, BaristaDisplay, and all other server
     * dependencies available to notebook snippets without any manual install step.
     */
    private void addServerClasspathToJShell() {
        // Strategy 1 — java.class.path (reliable for mvn spring-boot:run and
        // direct java -classpath invocations)
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            for (String entry : cp.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    try { jshell.addToClasspath(entry); } catch (Exception ignore) {}
                }
            }
        }

        // Strategy 2 — URLClassLoader chain (catches additional entries that
        // Spring Boot's classloader may have registered at runtime)
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            while (cl != null) {
                if (cl instanceof java.net.URLClassLoader ucl) {
                    for (java.net.URL url : ucl.getURLs()) {
                        try {
                            String path = java.nio.file.Paths.get(url.toURI()).toString();
                            jshell.addToClasspath(path);
                        } catch (Exception ignore) {}
                    }
                }
                cl = cl.getParent();
            }
        } catch (Exception ignore) {}
    }

    /** Execute code linked to a specific notebook cell (default runaway guards). */
    public synchronized ExecutionResult execute(String code, String cellId) {
        return execute(code, cellId, 30_000L, 1_000);
    }

    /**
     * Execute code with runaway guards. The snippet loop runs on a bounded worker thread so a
     * never-ending loop can be stopped via {@code jshell.stop()} without wedging the HTTP
     * thread or the per-session lock. Two limits apply:
     *   • {@code maxExecMs} — wall-clock compute budget; time spent blocked waiting on user
     *     input is excluded, so a user thinking at a prompt is never killed.
     *   • {@code maxOutputLines} — cap on printed lines, so a runaway printer can't OOM.
     * Either limit (or the manual Stop button) stops the cell and leaves the session usable.
     */
    public synchronized ExecutionResult execute(String code, String cellId,
                                                long maxExecMs, int maxOutputLines) {
        long startTime = System.currentTimeMillis();
        stopRequested = false;
        captureBuffer.reset();
        outputLineCount.set(0);

        Future<ExecutionResult> future = evalExecutor.submit(() -> runEval(code, cellId, startTime));

        long computeElapsed = 0;
        final long SLICE = 120;
        while (true) {
            try {
                return future.get(SLICE, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                if (stopRequested) {
                    stopEval();
                    return abortedResult(cellId, startTime, "Execution stopped by user.", "STOPPED");
                }
                if (maxOutputLines > 0 && outputLineCount.get() > maxOutputLines) {
                    stopEval();
                    return abortedResult(cellId, startTime,
                        "Output exceeded " + maxOutputLines + " lines and was stopped "
                        + "(possible runaway loop).", "OUTPUT_LIMIT");
                }
                // Time blocked waiting on user input does NOT count toward the compute budget.
                if (!BaristaInput.isWaitingForInput()) {
                    computeElapsed += SLICE;
                    if (maxExecMs > 0 && computeElapsed >= maxExecMs) {
                        stopEval();
                        return abortedResult(cellId, startTime,
                            "Execution exceeded " + (maxExecMs / 1000) + "s and was stopped "
                            + "(possible never-ending loop).", "TIMEOUT");
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                stopEval();
                return abortedResult(cellId, startTime, "Execution interrupted.", "STOPPED");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                return abortedResult(cellId, startTime,
                    "Internal execution error: " + cause.getMessage(), "ERROR");
            }
        }
    }

    /** Stop the currently-running snippet and unblock any pending stdin read. */
    private void stopEval() {
        try { BaristaInput.cancel(); } catch (Exception ignore) {}
        try { jshell.stop(); }        catch (Exception ignore) {}
    }

    /** Request that the running cell stop (manual Stop button / WebSocket interrupt). */
    public void requestStop() {
        stopRequested = true;
        stopEval();
    }

    /** Build a result for a cell that was stopped before completing normally. */
    private ExecutionResult abortedResult(String cellId, long startTime, String message, String status) {
        try { proxyStream.flush(); } catch (Exception ignore) {}
        String partial = captureBuffer.toString(StandardCharsets.UTF_8);
        return ExecutionResult.builder()
                .sessionId(sessionId)
                .cellId(cellId)
                .output(partial)
                .error(message)
                .status(status)
                .success(false)
                .executionTimeMs(System.currentTimeMillis() - startTime)
                .executionCount(executionCounter.incrementAndGet())
                .build();
    }

    /** The actual snippet evaluation — runs on the eval worker thread. */
    private ExecutionResult runEval(String code, String cellId, long startTime) {

        // Use JShell's SourceCodeAnalysis to split the input into individual complete
        // snippets, exactly as the JShell REPL does. This is critical: jshell.eval()
        // only processes ONE snippet per call. Without splitting, only the first
        // statement in a multi-line cell is executed and the rest are silently dropped,
        // causing "cannot find symbol" errors when later cells reference variables.
        SourceCodeAnalysis sca = jshell.sourceCodeAnalysis();
        String remaining = code;

        StringBuilder errors = new StringBuilder();
        String returnValue = null;
        boolean hasError = false;
        String status = "VALID";

        // Track names of variables declared (or overwritten) by THIS cell's snippets.
        // Used after execution to split the session's variables into "local" (this
        // cell) vs. "global" (carried in from earlier cells).
        Set<String> localVarNames = new LinkedHashSet<>();

        while (!remaining.isBlank()) {
            SourceCodeAnalysis.CompletionInfo info = sca.analyzeCompletion(remaining);
            String snippet = info.source();
            remaining = info.remaining();

            if (snippet == null || snippet.isBlank()) break;

            // JShell signals that a trailing semicolon is needed for expression statements
            String toEval = (info.completeness() == SourceCodeAnalysis.Completeness.COMPLETE_WITH_SEMI)
                    ? snippet + ";" : snippet;

            List<SnippetEvent> events = jshell.eval(toEval);

            for (SnippetEvent event : events) {
                if (event.exception() != null) {
                    Throwable ex = event.exception();
                    errors.append(ex.getClass().getSimpleName())
                          .append(": ")
                          .append(ex.getMessage() != null ? ex.getMessage() : ex.toString())
                          .append("\n");
                    hasError = true;
                    status = "ERROR";
                }
                if (event.value() != null && !"null".equals(event.value())) {
                    returnValue = event.value();
                }
                if (event.status() == Snippet.Status.REJECTED) {
                    jshell.diagnostics(event.snippet())
                          .forEach(d -> errors.append(formatDiag(d)).append("\n"));
                    hasError = true;
                    status = "REJECTED";
                } else if (!hasError && event.status() != null) {
                    status = event.status().name();
                }

                // Note any variable this cell declared / redeclared — used below
                // to classify it as a "local" variable for the debug panel.
                if (event.snippet() instanceof VarSnippet vs) {
                    localVarNames.add(vs.name());
                }
            }

            // Stop splitting if the remainder looks incomplete
            SourceCodeAnalysis.Completeness c = info.completeness();
            if (c == SourceCodeAnalysis.Completeness.DEFINITELY_INCOMPLETE
                    || c == SourceCodeAnalysis.Completeness.CONSIDERED_INCOMPLETE
                    || c == SourceCodeAnalysis.Completeness.UNKNOWN) {
                break;
            }
        }

        // Walk the full JShell variable table once and partition into local
        // (declared in this cell) vs. global (carried over from earlier cells).
        // Only VALID snippets are reported; overwritten / rejected ones are skipped.
        List<ExecutionResult.Variable> locals  = new ArrayList<>();
        List<ExecutionResult.Variable> globals = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            jshell.variables().forEach(vs -> {
                if (jshell.status(vs) != Snippet.Status.VALID) return;
                String name = vs.name();
                if (!seen.add(name)) return;   // skip earlier (overwritten) snippets with same name
                ExecutionResult.Variable v = new ExecutionResult.Variable(
                        name, vs.typeName(), safeVarValue(vs));
                if (localVarNames.contains(name)) locals.add(v);
                else                              globals.add(v);
            });
        } catch (Exception ignore) { /* don't fail the cell if introspection errors */ }

        // Flush and capture all stdout/stderr from this cell's execution
        proxyStream.flush();
        String output = captureBuffer.toString(StandardCharsets.UTF_8);

        int count = executionCounter.incrementAndGet();
        long elapsed = System.currentTimeMillis() - startTime;

        return ExecutionResult.builder()
                .sessionId(sessionId)
                .cellId(cellId)
                .output(output)
                .error(errors.toString())
                .returnValue(returnValue)
                .status(status)
                .success(!hasError)
                .executionTimeMs(elapsed)
                .executionCount(count)
                .localVariables(locals)
                .globalVariables(globals)
                .build();
    }

    /** Read a variable's value as a string, defensively — JShell can throw if the value reference is stale. */
    private String safeVarValue(VarSnippet vs) {
        try {
            String v = jshell.varValue(vs);
            return v == null ? "null" : v;
        } catch (Exception e) {
            return "<unavailable>";
        }
    }

    /** Convenience overload (no cell binding). */
    public synchronized ExecutionResult execute(String code) {
        return execute(code, null);
    }

    private String formatDiag(Diag diag) {
        String msg = diag.getMessage(null);
        StringBuilder sb = new StringBuilder();
        sb.append(diag.isError() ? "ERROR" : "WARNING").append(": ").append(msg);
        // Add contextual hints for common JShell errors
        if (msg != null) {
            if (msg.contains("cannot find symbol")) {
                sb.append("\n  Hint: Run the cell that declares this variable or class first." +
                          " In JShell mode, state is shared across cells — but only after each cell is executed.");
            } else if (msg.contains("illegal start of expression")) {
                sb.append("\n  Hint: In JShell mode, write top-level statements directly (no wrapping class/method)." +
                          " For a complete class with main(), switch this cell to Java mode.");
            } else if (msg.contains("class, interface, or enum expected")) {
                sb.append("\n  Hint: JShell executes snippets without requiring a class wrapper." +
                          " Remove 'public class ...' or switch to Java mode for full class compilation.");
            }
        }
        return sb.toString();
    }

    public synchronized void addJar(String jarPath) {
        if (!classpath.contains(jarPath)) {
            classpath.add(jarPath);
            jshell.addToClasspath(jarPath);
        }
    }

    public synchronized void restart() {
        if (jshell != null) jshell.close();
        captureBuffer.reset();
        initJShell();
        executionCounter.set(0);
    }

    public String getSessionId()      { return sessionId; }
    public List<String> getClasspath() { return Collections.unmodifiableList(classpath); }
    public int getExecutionCount()    { return executionCounter.get(); }

    /**
     * Drain and return all output accumulated in the capture buffer since the last drain.
     * Used during interactive stdin — flushes partial output to the browser before
     * blocking for user input, so the user can see what was printed before the prompt.
     */
    public String drainOutput() {
        String text = captureBuffer.toString(java.nio.charset.StandardCharsets.UTF_8);
        captureBuffer.reset();
        return text;
    }
    public JShell getJShell()         { return jshell; }

    public void close() {
        if (jshell != null) jshell.close();
        evalExecutor.shutdownNow();
    }
}
