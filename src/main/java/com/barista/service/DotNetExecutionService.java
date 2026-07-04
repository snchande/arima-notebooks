package com.barista.service;

import com.barista.model.ExecutionResult;
import com.barista.util.VariableInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.barista.model.NuGetPackageInfo;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * C# and F# execution service using the .NET SDK.
 *
 * <h3>C# cells</h3>
 * Requires only the <strong>.NET SDK</strong> — no extra tools:
 * <pre>dotnet run --project &lt;tempDir&gt;</pre>
 * Each cell runs as a top-level C# 9+ program inside a generated temp project.
 * NuGet packages installed via the NuGet tab are added as {@code <PackageReference>}
 * elements in the generated {@code .csproj}.
 *
 * <h3>F# cells</h3>
 * Requires the <strong>.NET SDK</strong>:
 * <pre>dotnet fsi --exec &lt;file.fsx&gt;</pre>
 * Inline {@code #r "nuget: PackageName, Version"} directives are extracted and
 * placed at the top of the script (before any {@code open} statements) so that
 * {@code dotnet fsi} can resolve them before compilation.
 *
 * <h3>Pipeline dependency injection (C# and F#)</h3>
 * C# and F# cells each run in an isolated subprocess — there is no shared REPL
 * state between cells.  To support {@code //@ depends:} annotations, this service
 * maintains a per-session anchor cache.  When a cell with {@code //@ anchor: name}
 * runs successfully, its source is stored.  When a later cell declares
 * {@code //@ depends: name}, the full transitive dependency closure is resolved
 * from the cache, each ancestor's code is injected into the program with output
 * suppressed (so only the current cell's output is visible), and the combined
 * program is compiled and run as a single unit.
 *
 * <h3>Arima helpers (C#)</h3>
 * <pre>
 *   BaristaHtml("&lt;b&gt;bold&lt;/b&gt;");    // rendered as inline HTML in output
 *   BaristaDisplay(myObject);          // Console.WriteLine
 *   BaristaTable(myList);              // ASCII table
 * </pre>
 *
 * <h3>Arima helpers (F#)</h3>
 * <pre>
 *   baristaHtml "&lt;b&gt;bold&lt;/b&gt;"    // rendered as inline HTML
 *   baristaDisplay myObj               // printfn "%A"
 *   baristaTable myList                // printfn "%A"
 * </pre>
 */
@Service
public class DotNetExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DotNetExecutionService.class);
    private static final int TIMEOUT_SECONDS = 120; // dotnet run includes restore + compile

    // C# preamble: standard usings + Arima helper methods
    private static final String CSHARP_PREAMBLE =
        "using System;\n" +
        "using System.IO;\n" +
        "using System.Linq;\n" +
        "using System.Collections.Generic;\n" +
        "using System.Text;\n\n" +
        "// Arima display helpers\n" +
        "static void BaristaHtml(string content) =>\n" +
        "    Console.WriteLine(\"BARISTA_HTML:\" + content.Replace(\"\\n\", \" \"));\n\n" +
        "static void BaristaDisplay(object obj) => Console.WriteLine(obj);\n\n" +
        "static void BaristaTable<T>(IEnumerable<T> items) {\n" +
        "    var list = items.ToList();\n" +
        "    if (!list.Any()) { Console.WriteLine(\"(empty)\"); return; }\n" +
        "    var props = typeof(T).GetProperties();\n" +
        "    var widths = props.Select(p => Math.Max(p.Name.Length,\n" +
        "        list.Max(r => (p.GetValue(r)?.ToString() ?? \"\").Length))).ToArray();\n" +
        "    var sep = \"+\" + string.Join(\"+\", widths.Select(w => new string('-', w + 2))) + \"+\";\n" +
        "    var hdr = \"|\" + string.Join(\"|\", props.Select((p,i) => \" \" + p.Name.PadRight(widths[i]) + \" \")) + \"|\";\n" +
        "    Console.WriteLine(sep); Console.WriteLine(hdr); Console.WriteLine(sep);\n" +
        "    foreach (var r in list)\n" +
        "        Console.WriteLine(\"|\" + string.Join(\"|\", props.Select((p,i) => \" \" + (p.GetValue(r)?.ToString() ?? \"\").PadRight(widths[i]) + \" \")) + \"|\");\n" +
        "    Console.WriteLine(sep);\n" +
        "    Console.WriteLine($\"{list.Count} row(s)\");\n" +
        "}\n\n";

    // F# preamble: standard opens + Arima helper functions
    private static final String FSHARP_PREAMBLE =
        "open System\n" +
        "open System.Linq\n" +
        "open System.Collections.Generic\n\n" +
        "// Arima display helpers\n" +
        "let baristaHtml (content: string) =\n" +
        "    printfn \"BARISTA_HTML:%s\" (content.Replace(\"\\n\", \" \"))\n\n" +
        "let baristaDisplay obj = printfn \"%A\" obj\n\n" +
        "let baristaTable (data: 'a list) =\n" +
        "    if List.isEmpty data then printfn \"(empty)\"\n" +
        "    else printfn \"%A\" data\n\n";

    private static final int CSHARP_PREAMBLE_LINES = CSHARP_PREAMBLE.split("\n", -1).length - 1;
    private static final int FSHARP_PREAMBLE_LINES  = FSHARP_PREAMBLE.split("\n", -1).length - 1;

    // Matches the start of a top-level type or namespace declaration, possibly prefixed
    // by access/modifier keywords (public, internal, abstract, sealed, partial, …).
    private static final java.util.regex.Pattern TYPE_DECL_RE =
            java.util.regex.Pattern.compile(
                "^(?:(?:public|private|internal|protected|sealed|abstract|static|partial|readonly|file)\\s+)*" +
                "(?:class|record|struct|interface|enum|namespace)\\s");

    private final NuGetService nuGetService;
    private final InteractiveProcessRunner runner;
    private final AtomicInteger execCounter = new AtomicInteger(0);

    /**
     * Per-session anchor source cache.
     * Key: sessionId → (anchorName → original cell source with annotation comments, without nuget directives)
     * Used to inject dependency code when a cell declares //@ depends: anchors.
     */
    private final Map<String, Map<String, String>> sessionAnchorSources = new ConcurrentHashMap<>();

    public DotNetExecutionService(NuGetService nuGetService, InteractiveProcessRunner runner) {
        this.nuGetService = nuGetService;
        this.runner = runner;
    }

    // ── C# execution ──────────────────────────────────────────────────────────

    /**
     * Execute C# code using {@code dotnet run} with a generated project file.
     *
     * <p>This approach requires only the .NET SDK — no dotnet-script.  The user's
     * code is written as a C# 9+ top-level program into {@code Program.cs} inside
     * a temp project directory, then compiled and run via
     * {@code dotnet run --project <dir>}.</p>
     *
     * <p>When the cell declares {@code //@ depends: anchorName}, the source of
     * each ancestor cell (resolved transitively from the session anchor cache) is
     * injected before the current cell's code with console output suppressed, so
     * that all variables and types defined by ancestor cells are in scope.</p>
     */
    public ExecutionResult executeCSharp(String sessionId, String cellId, String code) {
        long start = System.currentTimeMillis();

        String dotnet = findDotNet();
        if (dotnet == null) {
            return err(sessionId, cellId,
                ".NET SDK not found.\n\n" +
                "Install the .NET SDK from https://dot.net\n" +
                "  - Download: https://dotnet.microsoft.com/download\n" +
                "  - Verify: dotnet --version\n" +
                "  - Restart Arima after installing", start);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("barista-cs-");

            // Generate .csproj with NuGet package references
            String targetFramework = detectDotNetTargetFramework(dotnet);
            Files.writeString(tempDir.resolve("barista-cell.csproj"),
                    buildCsProj(targetFramework, nuGetService.getInstalledPackages()));

            // Parse annotations
            String anchor  = parseAnchor(code);
            List<String> depends = parseDepends(code);
            // Strip annotation comment lines and inline #r nuget directives before compilation
            String cleanCode = stripLeadingAnnotations(stripNuGetDirectives(code));

            Map<String, String> anchors = sessionAnchorSources
                    .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

            // Verify all declared dependencies are cached
            if (!depends.isEmpty()) {
                List<String> missing = depends.stream()
                        .filter(d -> !anchors.containsKey(d))
                        .collect(Collectors.toList());
                if (!missing.isEmpty()) {
                    return err(sessionId, cellId,
                            "Missing dependencies: " + String.join(", ", missing) +
                            "\nRun the dependency cells first, or click '→ Run with deps'.", start);
                }
            }

            // ── Build Program.cs ───────────────────────────────────────────
            StringBuilder programContent  = new StringBuilder(CSHARP_PREAMBLE);
            StringBuilder typeDeclarations = new StringBuilder();
            int prefixLineCount = CSHARP_PREAMBLE_LINES;

            if (!depends.isEmpty()) {
                // Resolve full transitive closure in topological (post-order DFS) order
                Set<String> visited = new LinkedHashSet<>();
                List<String> allDeps = resolveTransitiveDeps(depends, anchors, visited);

                // Collect executable statements and type declarations from all ancestors
                StringBuilder contextStmts = new StringBuilder();
                for (String dep : allDeps) {
                    String depClean = stripLeadingAnnotations(stripNuGetDirectives(anchors.get(dep)));
                    String[] parts = splitTypesAndStatements(depClean);
                    if (!parts[0].isBlank()) contextStmts.append(parts[0]);
                    if (!parts[1].isBlank()) typeDeclarations.append(parts[1]).append('\n');
                }

                // Inject ancestor code with output suppressed so only this cell's output appears
                if (!contextStmts.toString().isBlank()) {
                    programContent.append("var __baristaCtxOut = Console.Out;\n");
                    programContent.append("Console.SetOut(TextWriter.Null);\n");
                    programContent.append(contextStmts);
                    programContent.append("Console.SetOut(__baristaCtxOut);\n");
                    int ctxLines = (int) contextStmts.chars().filter(c -> c == '\n').count();
                    prefixLineCount += 3 + ctxLines; // 2 suppress + ctx + 1 restore
                }
            }

            // Add current cell's executable code
            String[] parts = splitTypesAndStatements(cleanCode);
            programContent.append(parts[0]);

            // Append a variable-dump trailer (best-effort — see retry block below).
            List<String> csVarNames = VariableInspector.parseCSharpDeclarations(parts[0]);
            String trailer = VariableInspector.buildCSharpTrailer(csVarNames);
            if (!trailer.isEmpty()) programContent.append(trailer);

            if (!parts[1].isBlank()) typeDeclarations.append(parts[1]);

            // All type declarations at the end (C# 9+ top-level program rule)
            if (!typeDeclarations.toString().isBlank()) {
                programContent.append('\n').append(typeDeclarations);
            }

            Path programCs = tempDir.resolve("Program.cs");
            Files.writeString(programCs, programContent.toString());

            // -v q suppresses MSBuild diagnostic output so only program output reaches stdout
            ProcessBuilder pb = new ProcessBuilder(
                    dotnet, "run", "--project", tempDir.toString(), "-v", "q");
            pb.redirectErrorStream(false);
            injectDotNetEnv(pb, dotnet);

            ExecutionResult result = runProcess(pb, programCs, sessionId, cellId, start,
                    prefixLineCount, "Program.cs");

            // If the trailer broke compilation (e.g. our parser invented a name
            // that doesn't actually exist in scope), retry once without the
            // trailer so the user still gets their original program's result.
            if (!result.isSuccess() && !trailer.isEmpty() && shouldRetryWithoutTrailer(result.getError())) {
                String programNoTrailer = programContent.toString().replace(trailer, "");
                Files.writeString(programCs, programNoTrailer);
                ProcessBuilder pb2 = new ProcessBuilder(
                        dotnet, "run", "--project", tempDir.toString(), "-v", "q");
                pb2.redirectErrorStream(false);
                injectDotNetEnv(pb2, dotnet);
                result = runProcess(pb2, programCs, sessionId, cellId, start,
                        prefixLineCount, "Program.cs");
            } else {
                // Strip the dump sentinels out of stdout and attach the parsed vars.
                VariableInspector.ParsedOutput parsed =
                        VariableInspector.parseDumpFromOutput(result.getOutput());
                result.setOutput(parsed.cleanedStdout);
                result.setLocalVariables(parsed.variables);
            }

            // On success, cache source so dependent cells can inject it as context
            if (result.isSuccess() && anchor != null && !anchor.isBlank()) {
                // Store with annotation comments intact so parseDepends() can do transitive resolution
                anchors.put(anchor, stripNuGetDirectives(code));
            }

            return result;
        } catch (Exception e) {
            log.error("C# execution error in cell {}", cellId, e);
            return err(sessionId, cellId, e.getClass().getSimpleName() + ": " + e.getMessage(), start);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    // ── F# execution ──────────────────────────────────────────────────────────

    /**
     * Execute F# code using {@code dotnet fsi} (F# Interactive, built into .NET SDK).
     * Requires: .NET SDK 6+ from https://dot.net
     *
     * <p>Inline {@code #r "nuget: PackageName, Version"} directives are extracted
     * from the user's code and placed at the very top of the script file (before any
     * {@code open} statements), ensuring {@code dotnet fsi} can resolve them before
     * the rest of the script is compiled.</p>
     *
     * <p>Pipeline dependency injection works the same way as for C# cells: ancestor
     * source is prepended with output suppressed.</p>
     */
    public ExecutionResult executeFSharp(String sessionId, String cellId, String code) {
        long start = System.currentTimeMillis();

        String dotnet = findDotNet();
        if (dotnet == null) {
            return err(sessionId, cellId,
                ".NET SDK not found.\n\n" +
                "Install the .NET SDK from https://dot.net\n" +
                "  - Download: https://dotnet.microsoft.com/download\n" +
                "  - Verify: dotnet --version\n" +
                "  - Restart Arima after installing", start);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("barista-fs-");
            Path scriptFile = tempDir.resolve("script.fsx");

            // Parse annotations
            String anchor  = parseAnchor(code);
            List<String> depends = parseDepends(code);
            // Strip only leading //@ annotation lines (keep inline #r and regular code)
            String cleanCode = stripLeadingAnnotations(code);

            // Extract inline #r "nuget:" directives — they must go before any open statements
            String[] nugetSplit = extractFSharpNuGetDirectives(cleanCode);
            String inlineNuGet  = nugetSplit[0];
            String codeBody     = nugetSplit[1];

            Map<String, String> anchors = sessionAnchorSources
                    .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

            // Verify declared dependencies are cached
            if (!depends.isEmpty()) {
                List<String> missing = depends.stream()
                        .filter(d -> !anchors.containsKey(d))
                        .collect(Collectors.toList());
                if (!missing.isEmpty()) {
                    return err(sessionId, cellId,
                            "Missing dependencies: " + String.join(", ", missing) +
                            "\nRun the dependency cells first, or click '→ Run with deps'.", start);
                }
            }

            // ── Build script.fsx ──────────────────────────────────────────
            // Structure: #r directives → preamble → [context with suppressed output] → cell code
            String nugetPreamble = nuGetService.buildNuGetPreamble();
            StringBuilder scriptContent = new StringBuilder();

            // All #r "nuget:" directives FIRST (NuGet tab packages + inline from cell)
            if (!nugetPreamble.isBlank()) scriptContent.append(nugetPreamble);
            if (!inlineNuGet.isBlank())   scriptContent.append(inlineNuGet);

            int nugetLines = countNewlines(nugetPreamble + inlineNuGet);
            scriptContent.append(FSHARP_PREAMBLE);
            int prefixLineCount = nugetLines + FSHARP_PREAMBLE_LINES;

            // Inject ancestor code with suppressed output
            if (!depends.isEmpty()) {
                Set<String> visited = new LinkedHashSet<>();
                List<String> allDeps = resolveTransitiveDeps(depends, anchors, visited);

                StringBuilder contextCode = new StringBuilder();
                for (String dep : allDeps) {
                    String depClean = stripLeadingAnnotations(anchors.get(dep));
                    // Don't re-inject inline #r directives from deps (they're already handled above)
                    String[] depNuget = extractFSharpNuGetDirectives(depClean);
                    String depBody = depNuget[1];
                    if (!depBody.isBlank()) contextCode.append(depBody).append('\n');
                }

                if (!contextCode.toString().isBlank()) {
                    scriptContent.append("let __baristaCtxOut = System.Console.Out\n");
                    scriptContent.append("System.Console.SetOut(System.IO.TextWriter.Null)\n");
                    scriptContent.append(contextCode);
                    scriptContent.append("System.Console.SetOut(__baristaCtxOut)\n");
                    int ctxLines = countNewlines(contextCode.toString());
                    prefixLineCount += 3 + ctxLines; // 2 suppress + ctx + 1 restore
                }
            }

            scriptContent.append(codeBody);

            // Append a variable-dump trailer — emit names of top-level `let`
            // bindings. F# (like C#) won't compile if we reference a name that
            // doesn't exist, so we retry without the trailer on failure.
            List<String> fsNames = VariableInspector.parseFSharpDeclarations(codeBody);
            String trailer = VariableInspector.buildFSharpTrailer(fsNames);
            if (!trailer.isEmpty()) scriptContent.append(trailer);

            Files.writeString(scriptFile, scriptContent.toString());

            ProcessBuilder pb = new ProcessBuilder(dotnet, "fsi", "--nologo", "--exec", scriptFile.toString());
            pb.redirectErrorStream(false);
            injectDotNetEnv(pb, dotnet);

            ExecutionResult result = runProcess(pb, scriptFile, sessionId, cellId, start,
                    prefixLineCount, "script.fsx");

            // Retry without the trailer if it caused a compile error.
            if (!result.isSuccess() && !trailer.isEmpty() && shouldRetryWithoutTrailer(result.getError())) {
                String scriptNoTrailer = scriptContent.toString().replace(trailer, "");
                Files.writeString(scriptFile, scriptNoTrailer);
                ProcessBuilder pb2 = new ProcessBuilder(dotnet, "fsi", "--nologo", "--exec", scriptFile.toString());
                pb2.redirectErrorStream(false);
                injectDotNetEnv(pb2, dotnet);
                result = runProcess(pb2, scriptFile, sessionId, cellId, start,
                        prefixLineCount, "script.fsx");
            } else {
                VariableInspector.ParsedOutput parsed =
                        VariableInspector.parseDumpFromOutput(result.getOutput());
                result.setOutput(parsed.cleanedStdout);
                result.setLocalVariables(parsed.variables);
            }

            // On success, cache source for dependency injection
            if (result.isSuccess() && anchor != null && !anchor.isBlank()) {
                anchors.put(anchor, code); // keep original with annotation comments
            }

            return result;
        } catch (Exception e) {
            log.error("F# execution error in cell {}", cellId, e);
            return err(sessionId, cellId, e.getClass().getSimpleName() + ": " + e.getMessage(), start);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    // ── Availability checks ────────────────────────────────────────────────────

    /** Returns true if the .NET SDK (dotnet) is on the PATH. */
    public boolean isDotNetAvailable() {
        return findDotNet() != null;
    }

    /** Returns true if dotnet-script global tool is installed. */
    public boolean isDotNetScriptAvailable() {
        return findDotNetScript() != null;
    }

    /**
     * Clear the anchor source cache for a session.
     * Call this after a kernel/session restart so stale dependency sources are evicted.
     */
    public void clearSessionAnchors(String sessionId) {
        sessionAnchorSources.remove(sessionId);
        log.debug("[DotNet] Cleared anchor cache for session '{}'", sessionId);
    }

    /**
     * Directly insert an anchor source into the session cache without executing it.
     * Used by {@link OrchestrationService} when loading cross-notebook C#/F# module cells —
     * instead of executing them via JShell (which would fail for .NET code), their expanded
     * source is pre-loaded here so dependent cells can inject it via {@code //@ depends:}.
     *
     * @param sessionId the target session
     * @param key       the cache key — for cross-notebook refs this is {@code "notebook:notebookId/anchor"}
     * @param source    the full, ready-to-inject source (already annotation-stripped and dep-expanded)
     */
    public void cacheAnchorSource(String sessionId, String key, String source) {
        sessionAnchorSources.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                            .put(key, source);
        log.debug("[DotNet] Pre-cached anchor '{}' for session '{}'", key, sessionId);
    }

    // ── Annotation helpers ─────────────────────────────────────────────────────

    /** Parse {@code //@ anchor: name} from leading annotation comment lines. */
    private String parseAnchor(String code) {
        if (code == null) return null;
        for (String line : code.split("\n", -1)) {
            String t = line.strip();
            if (!t.startsWith("//@")) break;
            String rest = t.substring(3).strip();
            if (rest.startsWith("anchor:")) return rest.substring(7).strip();
        }
        return null;
    }

    /** Parse {@code //@ depends: a, b, c} from leading annotation comment lines. */
    private List<String> parseDepends(String code) {
        if (code == null) return List.of();
        for (String line : code.split("\n", -1)) {
            String t = line.strip();
            if (!t.startsWith("//@")) break;
            String rest = t.substring(3).strip();
            if (rest.startsWith("depends:")) {
                return Arrays.stream(rest.substring(8).split(","))
                             .map(String::strip)
                             .filter(s -> !s.isEmpty())
                             .collect(Collectors.toList());
            }
        }
        return List.of();
    }

    /** Remove only the leading {@code //@} annotation comment lines from source code. */
    private String stripLeadingAnnotations(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean past = false;
        for (String line : code.split("\n", -1)) {
            if (!past && line.strip().startsWith("//@")) continue;
            past = true;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Resolve transitive dependency anchor names in topological (post-order DFS) order.
     * The returned list contains each anchor name exactly once, ancestors before dependents.
     */
    private List<String> resolveTransitiveDeps(List<String> directDeps,
                                               Map<String, String> anchorSources,
                                               Set<String> visited) {
        List<String> result = new ArrayList<>();
        for (String dep : directDeps) {
            if (!visited.add(dep)) continue; // already resolved
            String depSource = anchorSources.get(dep);
            if (depSource != null) {
                // Recurse into this dep's own declared dependencies
                List<String> transitives = parseDepends(depSource);
                result.addAll(resolveTransitiveDeps(transitives, anchorSources, visited));
            }
            result.add(dep);
        }
        return result;
    }

    /**
     * Extract uncommented {@code #r "nuget: …"} directives from F# source.
     * Returns {@code [directives, remainingCode]}.
     * Directives must be placed before any {@code open} statements for {@code dotnet fsi}.
     */
    private String[] extractFSharpNuGetDirectives(String code) {
        if (code == null) return new String[]{"", ""};
        StringBuilder directives = new StringBuilder();
        StringBuilder remaining  = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("#r \"nuget:") || t.startsWith("#r\"nuget:")) {
                directives.append(line).append('\n');
            } else {
                remaining.append(line).append('\n');
            }
        }
        return new String[]{ directives.toString(), remaining.toString() };
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private ExecutionResult runProcess(ProcessBuilder pb, Path scriptFile,
                                       String sessionId, String cellId,
                                       long start, int preambleLines, String scriptName)
            throws IOException, InterruptedException {

        // Interactive stdin (UI runs) + runaway guards via the shared runner. The build
        // phase of `dotnet run` / `dotnet fsi` streams too, then the program can prompt.
        InteractiveProcessRunner.ProcRun run = runner.run(pb);

        if (run.timedOut()) {
            return err(sessionId, cellId,
                "Execution timed out after " + TIMEOUT_SECONDS
                + " seconds (possible never-ending loop) and was stopped.", start);
        }

        long elapsed = System.currentTimeMillis() - start;
        int exitCode  = run.exitCode();
        String tempDirStr = scriptFile.getParent().toString();

        // Remove temp file paths from both stdout and stderr
        String cleanOut = run.stdout()
                .replace(scriptFile.toString(), scriptName)
                .replace(tempDirStr + File.separator, "")
                .replace(tempDirStr + "/", "")
                .replace(tempDirStr, "");

        String errStr = run.stderr().trim();
        String cleanErr = errStr
                .replace(scriptFile.toString(), scriptName)
                .replace(tempDirStr + File.separator, "")
                .replace(tempDirStr + "/", "")
                .replace(tempDirStr, "");

        // Adjust line numbers to account for injected preamble
        if (preambleLines > 0) {
            String base = scriptName.replace(".fsx", "").replace(".csx", "").replace(".cs", "");
            cleanErr = shiftLineNumbers(cleanErr, base, preambleLines);
            cleanOut = shiftLineNumbers(cleanOut, base, preambleLines);
        }

        boolean success = exitCode == 0;

        return ExecutionResult.builder()
                .sessionId(sessionId).cellId(cellId)
                .output(cleanOut)
                .error(success ? "" : cleanErr)
                .status(success ? "OK" : "RUNTIME_ERROR")
                .success(success)
                .executionTimeMs(elapsed)
                .executionCount(execCounter.incrementAndGet())
                .build();
    }

    /**
     * Decide whether a failed C#/F# run is likely caused by our injected
     * variable-dump trailer (parser invented a name that doesn't exist).
     * Triggered by CS0103 / FS0039 (undefined identifier) and CS1002/CS1003
     * (parse errors in our injected block).
     */
    private boolean shouldRetryWithoutTrailer(String error) {
        if (error == null) return false;
        return error.contains("CS0103")           // C#: "The name '...' does not exist"
            || error.contains("CS1002")           // C#: missing ;
            || error.contains("CS1003")           // C#: syntax error
            || error.contains("CS0246")           // C#: type/namespace not found (rare in trailer)
            || error.contains("FS0039")           // F#: value/constructor/namespace not defined
            || error.contains("FS0589")           // F#: unexpected end of input
            || error.contains("__barista_emit");    // our trailer's helper name
    }

    /** Shift line numbers in compiler/runtime error messages by subtracting preamble offset. */
    private String shiftLineNumbers(String error, String baseName, int offset) {
        if (error == null || error.isEmpty()) return error;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\()(\\d+)(,\\d+\\))")
                .matcher(error);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int lineNum = Integer.parseInt(m.group(2));
            int adjusted = Math.max(1, lineNum - offset);
            m.appendReplacement(sb, m.group(1) + adjusted + m.group(3));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Inject .NET-related environment variables into a ProcessBuilder.
     */
    private void injectDotNetEnv(ProcessBuilder pb, String dotnetExe) {
        pb.environment().put("DOTNET_NOLOGO", "1");
        pb.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");

        if (dotnetExe == null) return;

        String dotnetDir = new File(dotnetExe).getParentFile() != null
                ? new File(dotnetExe).getParentFile().getAbsolutePath()
                : null;
        if (dotnetDir == null) return;

        // Find the PATH key case-insensitively (Windows uses "Path", Linux "PATH")
        String pathKey = null;
        String existingPath = "";
        for (java.util.Map.Entry<String, String> entry : pb.environment().entrySet()) {
            if ("path".equalsIgnoreCase(entry.getKey())) {
                pathKey = entry.getKey();
                existingPath = entry.getValue();
                break;
            }
        }
        if (pathKey == null) {
            pathKey = System.getProperty("os.name", "").toLowerCase().contains("win") ? "Path" : "PATH";
        }

        String sep = System.getProperty("os.name", "").toLowerCase().contains("win") ? ";" : ":";
        pb.environment().put(pathKey, dotnetDir + sep + existingPath);
        pb.environment().put("DOTNET_ROOT", dotnetDir);
        log.debug("Injected dotnet dir '{}' into {} and DOTNET_ROOT", dotnetDir, pathKey);
    }

    /** Detect the installed .NET SDK major version and return the target framework moniker. */
    private String detectDotNetTargetFramework(String dotnet) {
        try {
            ProcessBuilder pb = new ProcessBuilder(dotnet, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && !output.isBlank()) {
                String major = output.split("\\.")[0];
                return "net" + major + ".0";
            }
        } catch (Exception ignored) {}
        return "net8.0";
    }

    /** Generate a minimal {@code .csproj} for a top-level C# program. */
    private String buildCsProj(String targetFramework, List<NuGetPackageInfo> packages) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Project Sdk=\"Microsoft.NET.Sdk\">\n")
          .append("  <PropertyGroup>\n")
          .append("    <OutputType>Exe</OutputType>\n")
          .append("    <TargetFramework>").append(targetFramework).append("</TargetFramework>\n")
          .append("    <Nullable>enable</Nullable>\n")
          .append("    <ImplicitUsings>enable</ImplicitUsings>\n")
          .append("    <AllowUnsafeBlocks>true</AllowUnsafeBlocks>\n")
          .append("    <NoWarn>CS8321</NoWarn>\n") // suppress "local function declared but never used"
          .append("  </PropertyGroup>\n");
        if (!packages.isEmpty()) {
            sb.append("  <ItemGroup>\n");
            for (NuGetPackageInfo pkg : packages) {
                sb.append("    <PackageReference Include=\"").append(pkg.getPackageId())
                  .append("\" Version=\"").append(pkg.getVersion()).append("\" />\n");
            }
            sb.append("  </ItemGroup>\n");
        }
        sb.append("</Project>\n");
        return sb.toString();
    }

    /** Strip {@code #r "nuget: ..."} directives — dotnet-script syntax, invalid in standard C#. */
    private String stripNuGetDirectives(String code) {
        return code.lines()
                   .filter(line -> !line.strip().startsWith("#r \"nuget:"))
                   .collect(Collectors.joining("\n"));
    }

    /**
     * Split user C# code into two sections to satisfy C# 9+ top-level program rules
     * (CS8803: top-level statements must precede type declarations).
     *
     * <p>Returns {@code [executableCode, typeDeclarations]}.
     * Type/namespace declarations at brace depth 0 are moved to {@code typeDeclarations};
     * everything else stays in {@code executableCode}.  The caller generates
     * {@code PREAMBLE + executableCode + typeDeclarations}.</p>
     */
    /**
     * Split C# 9+ top-level program source into:
     *   parts[0] — executable top-level statements
     *   parts[1] — type/namespace declarations (class, record, struct, interface, enum, namespace)
     *
     * <p>Handles three common C# declaration styles:
     * <ul>
     *   <li>Brace body, same line: {@code class Foo { ... }}</li>
     *   <li>Brace body, Allman style: {@code class Foo\n{}</li>
     *   <li>Positional record: {@code record Point(int X, int Y);}</li>
     * </ul>
     *
     * <p>After detecting a TYPE_DECL_RE match at depth 0, lines are accumulated into
     * {@code declarations} until the type's body is fully consumed.  "Opened" tracks
     * whether a {@code {}, {@code (}, or a class/record body delimiter has been seen on
     * any line so far — the loop continues until both brace and paren depths return to 0
     * AND at least one delimiter was opened.  This lets the method handle Allman-style
     * declarations where the opening {@code {}} is on the line after the type header.
     */
    private String[] splitTypesAndStatements(String code) {
        StringBuilder statements    = new StringBuilder();
        StringBuilder declarations  = new StringBuilder();

        String[] lines = code.split("\n", -1);
        int i          = 0;
        int braceDepth = 0;

        while (i < lines.length) {
            String line    = lines[i];
            String trimmed = line.stripLeading();

            if (braceDepth == 0 && TYPE_DECL_RE.matcher(trimmed).find()) {
                // ── type/namespace declaration ──────────────────────────────
                // Accumulate lines until the type body is fully closed.
                // 'opened' becomes true once a { or ( has been seen; the loop
                // then continues until both depths return to 0.  This handles:
                //   class Foo { ... }       — opens and closes on one line
                //   class Foo\n{ ... }      — Allman-style (opened on next line)
                //   record Foo(int X, ...); — positional record (paren-style)
                boolean opened = false;
                int parenDepth = 0;

                do {
                    String body = lines[i];
                    boolean lineHasOpen = body.indexOf('{') >= 0 || body.indexOf('(') >= 0;
                    braceDepth += countChar(body, '{') - countChar(body, '}');
                    parenDepth += countChar(body, '(') - countChar(body, ')');
                    if (lineHasOpen) opened = true;
                    declarations.append(body).append('\n');
                    i++;
                } while (i < lines.length && (!opened || braceDepth > 0 || parenDepth > 0));
                // Both depths at 0 and at least one delimiter seen → type fully consumed.

            } else {
                // ── executable statement ────────────────────────────────────
                // Track brace depth so nested { } inside statements (like
                // collection initializers) don't trigger type detection mid-body.
                braceDepth += countChar(line, '{') - countChar(line, '}');
                statements.append(line).append('\n');
                i++;
            }
        }

        return new String[]{ statements.toString(), declarations.toString() };
    }

    private int countChar(String s, char target) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) n++;
        }
        return n;
    }

    private int countNewlines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** Find dotnet-script executable (global .NET tool). */
    private String findDotNetScript() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String home = System.getProperty("user.home", "");

        List<String> candidates = new ArrayList<>(List.of(
            home + "/.dotnet/tools/dotnet-script",
            home + "/.dotnet/tools/dotnet-script.exe",
            home + "/AppData/Local/Microsoft/dotnet/tools/dotnet-script.exe",
            home + "/AppData/Roaming/dotnet/tools/dotnet-script.exe"
        ));

        for (String path : candidates) {
            if (new File(path).exists()) {
                log.debug("Found dotnet-script at: {}", path);
                return path;
            }
        }

        return findOnPath(isWindows ? new String[]{"dotnet-script.exe", "dotnet-script.cmd"}
                                    : new String[]{"dotnet-script"});
    }

    /** Find the dotnet CLI executable. */
    private String findDotNet() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        List<String> candidates = new ArrayList<>(List.of(
            "C:/Program Files/dotnet/dotnet.exe",
            "C:/Program Files (x86)/dotnet/dotnet.exe",
            "/usr/local/share/dotnet/dotnet",
            "/usr/share/dotnet/dotnet",
            "/opt/homebrew/bin/dotnet"
        ));

        for (String path : candidates) {
            if (new File(path).exists()) {
                log.debug("Found dotnet at: {}", path);
                return path;
            }
        }

        return findOnPath(isWindows ? new String[]{"dotnet.exe"} : new String[]{"dotnet"});
    }

    private String findOnPath(String[] names) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (String name : names) {
            try {
                List<String> cmd = isWindows
                    ? List.of("cmd", "/c", "where", name)
                    : List.of("which", name);
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String result = new String(p.getInputStream().readAllBytes()).trim();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && !result.isBlank()) {
                    String found = result.split("\\r?\\n")[0].trim();
                    log.debug("Found {} via PATH: {}", name, found);
                    return found;
                }
            } catch (Exception ignored) {}
        }
        return null;
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
