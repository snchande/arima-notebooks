package com.barista.service;

import com.barista.model.ExecutionResult;
import com.barista.util.VariableInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full Java compilation and execution service.
 *
 * Unlike JShell (which runs snippets in a shared session), this service:
 *  - Compiles each cell as a complete, self-contained Java program
 *  - Supports full class declarations with main() methods
 *  - Auto-wraps bare statements into a Main class when no class is detected
 *  - Respects installed Maven packages (added to compile + runtime classpath)
 *  - Each execution is independent (no shared state between Java-mode cells)
 *
 * WHY SUPPORT BOTH JSHELL AND FULL JAVA?
 *  JShell excels at exploration: no boilerplate, shared variables, instant feedback.
 *  Full Java excels at production patterns: OOP, multiple classes, package-level visibility,
 *  static initializers, complex generics, and code that mirrors real applications.
 *  Together they cover the complete Java learning and development spectrum.
 */
@Service
public class JavaCompilerService {

    private static final Logger log = LoggerFactory.getLogger(JavaCompilerService.class);
    private static final int TIMEOUT_SECONDS = 30;

    private final AtomicInteger execCounter = new AtomicInteger(0);
    private final InteractiveProcessRunner runner;

    public JavaCompilerService(InteractiveProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Compile and run a complete Java program.
     *
     * @param sessionId  Notebook session (used for result metadata only)
     * @param cellId     Cell that triggered this execution
     * @param code       Java source code (full class or bare statements)
     * @param classpath  List of JAR paths to include on classpath
     */
    public ExecutionResult execute(String sessionId, String cellId,
                                   String code, List<String> classpath) {
        long start = System.currentTimeMillis();

        String prepared = prepareCode(code);

        // Inject a variable-dump trailer into the body of main(...) — the
        // user gets a Variables panel with their locals (best-effort; only
        // primitive/String/var declarations our parser recognises). If the
        // injection breaks compilation we retry without it.
        List<String> javaLocals = VariableInspector.parseJavaMainLocals(prepared);
        String injectedTrailer  = VariableInspector.buildJavaTrailer(javaLocals);
        String preparedWithTrailer = injectedTrailer.isEmpty()
                ? prepared
                : injectIntoMainEnd(prepared, injectedTrailer);

        String className = extractClassName(prepared);
        // The source we'll actually compile — starts with the trailer-augmented
        // version, may be rewritten to `prepared` (sans trailer) on retry.
        String sourceToCompile = preparedWithTrailer;

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("barista-java-");

            Path sourceFile = tempDir.resolve(className + ".java");
            Files.writeString(sourceFile, sourceToCompile);

            // ── Compile ───────────────────────────────────────────────
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return err(sessionId, cellId,
                    "Java compiler not available.\n" +
                    "Ensure a full JDK (not just JRE) is installed and JAVA_HOME points to it.",
                    start);
            }

            List<String> options = buildCompileOptions(tempDir, classpath);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean compiledOk = false;
            try (StandardJavaFileManager fm =
                         compiler.getStandardFileManager(diagnostics, null, null)) {
                fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempDir.toFile()));
                Iterable<? extends JavaFileObject> units =
                        fm.getJavaFileObjects(sourceFile.toFile());
                JavaCompiler.CompilationTask task =
                        compiler.getTask(null, fm, diagnostics, options, null, units);
                compiledOk = task.call();
            }

            // If our injected trailer broke compilation, retry once without it
            // so the user's original code still runs (just without a vars panel).
            if (!compiledOk && !injectedTrailer.isEmpty()) {
                sourceToCompile = prepared;
                injectedTrailer = "";
                Files.writeString(sourceFile, sourceToCompile);
                diagnostics = new DiagnosticCollector<>();
                try (StandardJavaFileManager fm =
                             compiler.getStandardFileManager(diagnostics, null, null)) {
                    fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempDir.toFile()));
                    Iterable<? extends JavaFileObject> units =
                            fm.getJavaFileObjects(sourceFile.toFile());
                    JavaCompiler.CompilationTask task =
                            compiler.getTask(null, fm, diagnostics, options, null, units);
                    compiledOk = task.call();
                }
            }

            if (!compiledOk) {
                StringBuilder errors = new StringBuilder();
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        errors.append("Line ").append(d.getLineNumber())
                              .append(": ").append(d.getMessage(null)).append("\n");
                    }
                }
                long elapsed = System.currentTimeMillis() - start;
                return ExecutionResult.builder()
                        .sessionId(sessionId).cellId(cellId)
                        .output("").error(errors.toString().trim())
                        .status("COMPILE_ERROR").success(false)
                        .executionTimeMs(elapsed)
                        .executionCount(execCounter.incrementAndGet())
                        .build();
            }

            // ── Run ───────────────────────────────────────────────────
            // Only the compiled program runs interactively; javac above stays batch.
            List<String> cmd = buildRunCommand(tempDir, classpath, className);
            ProcessBuilder pb = new ProcessBuilder(cmd);

            InteractiveProcessRunner.ProcRun run = runner.run(pb);

            if (run.timedOut()) {
                return err(sessionId, cellId,
                    "Execution timed out after " + TIMEOUT_SECONDS
                    + " seconds (possible never-ending loop) and was stopped.", start);
            }

            long elapsed = System.currentTimeMillis() - start;
            int exitCode = run.exitCode();
            String errStr = run.stderr().trim();
            boolean success = exitCode == 0 && errStr.isEmpty();

            // Pull out the variable-dump sentinel block (if the trailer ran).
            VariableInspector.ParsedOutput parsed =
                    VariableInspector.parseDumpFromOutput(run.stdout());

            return ExecutionResult.builder()
                    .sessionId(sessionId).cellId(cellId)
                    .output(parsed.cleanedStdout)
                    .error(errStr)
                    .status(success ? "OK" : "RUNTIME_ERROR")
                    .success(success)
                    .executionTimeMs(elapsed)
                    .executionCount(execCounter.incrementAndGet())
                    .localVariables(parsed.variables)
                    .build();

        } catch (Exception e) {
            log.error("Java execution error in cell {}", cellId, e);
            return err(sessionId, cellId,
                e.getClass().getSimpleName() + ": " + e.getMessage(), start);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Splice {@code trailer} into the source just before main()'s closing
     * brace. Walks the source character-by-character, tracking string/char
     * literals and {@code //} / {@code /* * /} comments so braces inside them
     * don't throw the depth counter off. Returns the original source if
     * main() can't be located.
     */
    private String injectIntoMainEnd(String source, String trailer) {
        Pattern mainPat = Pattern.compile("\\bpublic\\s+static\\s+void\\s+main\\s*\\([^)]*\\)\\s*\\{");
        Matcher m = mainPat.matcher(source);
        if (!m.find()) return source;
        int bodyStart = m.end() - 1;        // position of the opening '{'
        int depth = 0;
        int closingBrace = -1;
        boolean inLine = false, inBlock = false, inStr = false, inChar = false;
        char prev = '\0';
        for (int i = bodyStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inLine) { if (c == '\n') inLine = false; prev = c; continue; }
            if (inBlock) { if (prev == '*' && c == '/') inBlock = false; prev = c; continue; }
            if (inStr)  { if (c == '\\' && i + 1 < source.length()) { i++; prev = '\0'; continue; } if (c == '"')  inStr  = false; prev = c; continue; }
            if (inChar) { if (c == '\\' && i + 1 < source.length()) { i++; prev = '\0'; continue; } if (c == '\'') inChar = false; prev = c; continue; }
            if (c == '/' && i + 1 < source.length()) {
                char n = source.charAt(i + 1);
                if (n == '/') { inLine = true;  i++; prev = '\0'; continue; }
                if (n == '*') { inBlock = true; i++; prev = '\0'; continue; }
            }
            if (c == '"')  { inStr  = true; prev = c; continue; }
            if (c == '\'') { inChar = true; prev = c; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { closingBrace = i; break; } }
            prev = c;
        }
        if (closingBrace < 0) return source;
        return source.substring(0, closingBrace) + trailer + source.substring(closingBrace);
    }

    /**
     * If the code has no class declaration, wrap it in a Main class with a main method.
     * This allows users to write bare statements like JShell while still using full Java semantics.
     */
    private String prepareCode(String code) {
        // Check if there is already a class declaration
        if (Pattern.compile("\\bclass\\s+\\w+").matcher(code).find()) {
            return code;
        }
        // Wrap bare code in boilerplate
        StringBuilder sb = new StringBuilder();
        sb.append("public class Main {\n");
        sb.append("    public static void main(String[] args) throws Exception {\n");
        for (String line : code.split("\n")) {
            sb.append("        ").append(line).append("\n");
        }
        sb.append("    }\n}\n");
        return sb.toString();
    }

    private String extractClassName(String code) {
        // Prefer public class name
        Matcher m = Pattern.compile("public\\s+class\\s+(\\w+)").matcher(code);
        if (m.find()) return m.group(1);
        // Fall back to first class name
        m = Pattern.compile("\\bclass\\s+(\\w+)").matcher(code);
        if (m.find()) return m.group(1);
        return "Main";
    }

    private List<String> buildCompileOptions(Path outputDir, List<String> classpath) {
        List<String> opts = new ArrayList<>();
        opts.add("--release"); opts.add("21");
        if (!classpath.isEmpty()) {
            opts.add("-cp");
            opts.add(String.join(File.pathSeparator, classpath));
        }
        return opts;
    }

    private List<String> buildRunCommand(Path outputDir, List<String> classpath, String className) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        List<String> cp = new ArrayList<>();
        cp.add(outputDir.toString());
        cp.addAll(classpath);
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));
        cmd.add(className);
        return cmd;
    }

    private ExecutionResult err(String sessionId, String cellId, String error, long start) {
        return ExecutionResult.builder()
                .sessionId(sessionId).cellId(cellId)
                .output("").error(error != null ? error : "Unknown error")
                .status("ERROR").success(false)
                .executionTimeMs(System.currentTimeMillis() - start)
                .executionCount(0)
                .build();
    }

    private void deleteTempDir(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {}
    }
}
