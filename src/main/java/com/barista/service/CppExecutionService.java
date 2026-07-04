package com.barista.service;

import com.barista.model.ExecutionResult;
import com.barista.util.VariableInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CppExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CppExecutionService.class);
    private static final int TIMEOUT_SECONDS = 60;

    private enum CompilerType { GXX, CLANGXX, MSVC }

    private static class CompilerInfo {
        final CompilerType type;
        /** g++/clang++: executable name; MSVC: full path to vcvars64.bat */
        final String command;
        CompilerInfo(CompilerType type, String command) {
            this.type = type;
            this.command = command;
        }
    }

    // Standard headers + Arima helper functions injected into every cell
    private static final String BARISTA_PREAMBLE =
        "#define _USE_MATH_DEFINES\n" +   // enables M_PI, M_E, M_SQRT2 etc. on MSVC
        "#include <iostream>\n" +
        "#include <string>\n" +
        "#include <vector>\n" +
        "#include <map>\n" +
        "#include <unordered_map>\n" +
        "#include <set>\n" +
        "#include <list>\n" +
        "#include <deque>\n" +
        "#include <queue>\n" +
        "#include <stack>\n" +
        "#include <algorithm>\n" +
        "#include <numeric>\n" +
        "#include <functional>\n" +
        "#include <sstream>\n" +
        "#include <iomanip>\n" +
        "#include <cmath>\n" +
        "#include <memory>\n" +
        "#include <optional>\n" +
        "#include <variant>\n" +
        "#include <tuple>\n" +
        "#include <array>\n" +
        "#include <stdexcept>\n" +
        "#include <fstream>\n" +
        "#include <random>\n" +
        "#include <chrono>\n" +
        "#include <typeinfo>\n" +
        "using namespace std;\n\n" +
        "// Arima display helpers\n" +
        "void baristaHtml(const string& content) {\n" +
        "    string s = content;\n" +
        "    replace(s.begin(), s.end(), '\\n', ' ');\n" +
        "    cout << \"BARISTA_HTML:\" << s << \"\\n\";\n" +
        "}\n\n" +
        "template<typename T>\n" +
        "void baristaDisplay(const T& obj) {\n" +
        "    cout << obj << \"\\n\";\n" +
        "}\n\n" +
        "template<typename T>\n" +
        "void baristaTable(const vector<T>& items) {\n" +
        "    if (items.empty()) { cout << \"(empty)\\n\"; return; }\n" +
        "    cout << items.size() << \" item(s)\\n\";\n" +
        "}\n\n" +
        "template<typename K, typename V>\n" +
        "void baristaTable(const map<K,V>& m) {\n" +
        "    if (m.empty()) { cout << \"(empty)\\n\"; return; }\n" +
        "    cout << setw(20) << left << \"Key\" << \" | \" << \"Value\\n\";\n" +
        "    cout << string(40, '-') << \"\\n\";\n" +
        "    for (const auto& [k,v] : m)\n" +
        "        cout << setw(20) << left << k << \" | \" << v << \"\\n\";\n" +
        "    cout << m.size() << \" entry(s)\\n\";\n" +
        "}\n\n" +
        "// Null streambuf for suppressing output during dependency injection\n" +
        "struct __BaristaNullBuf : streambuf {\n" +
        "    int overflow(int c) override { return c; }\n" +
        "};\n\n";

    private static final int PREAMBLE_LINE_COUNT = BARISTA_PREAMBLE.split("\n", -1).length - 1;

    // Annotation patterns
    private static final Pattern ANCHOR_RE  = Pattern.compile("^//@ *anchor: *(.+)$", Pattern.MULTILINE);
    private static final Pattern DEPENDS_RE = Pattern.compile("^//@ *depends: *(.+)$", Pattern.MULTILINE);

    // Detects top-level declarations that must live at global scope
    private static final Pattern DECL_START_RE = Pattern.compile(
        "^(?:template\\s*<[^>]*>\\s*)?" +
        "(?:inline\\s+|static\\s+|extern\\s+|constexpr\\s+|consteval\\s+|constinit\\s+)*" +
        "(?:class|struct|enum(?:\\s+class)?|union|namespace|typedef|using\\s+\\w)");

    // Function definition heuristic: word chars before ( — not a control-flow keyword
    private static final Pattern FUNC_DEF_RE = Pattern.compile(
        "^[\\w:*&<>~]+(\\s+[\\w:*&<>~]+)*\\s*\\(");

    private final Map<String, Map<String, String>> sessionAnchorSources = new ConcurrentHashMap<>();
    private final AtomicInteger execCounter = new AtomicInteger(0);
    private final InteractiveProcessRunner runner;

    public CppExecutionService(InteractiveProcessRunner runner) {
        this.runner = runner;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public ExecutionResult execute(String sessionId, String cellId, String code) {
        long start = System.currentTimeMillis();

        CompilerInfo compiler = findCompilerInfo();
        if (compiler == null) {
            return err(sessionId, cellId, buildNotFoundMessage(), start);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("barista-cpp-");
            Path sourceFile = tempDir.resolve("main.cpp");
            Path exeFile    = tempDir.resolve(isWindows() ? "main.exe" : "main");

            // Parse annotations
            String anchor         = parseAnchor(code);
            List<String> depends  = parseDepends(code);
            String cleanCode      = stripAnnotations(code);

            Map<String, String> anchors = sessionAnchorSources
                    .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

            if (!depends.isEmpty()) {
                List<String> missing = depends.stream()
                        .filter(d -> !anchors.containsKey(d))
                        .collect(Collectors.toList());
                if (!missing.isEmpty()) {
                    return err(sessionId, cellId,
                        "Missing C++ dependencies: " + String.join(", ", missing) +
                        "\nRun the dependency cells first, or use '→ Run with deps'.", start);
                }
            }

            boolean completeProgram = hasMainFunction(cleanCode) && depends.isEmpty();
            String programSource;
            int prefixLines;
            // Trailer captured for the retry-on-compile-failure fallback.
            String injectedTrailer = "";

            if (completeProgram) {
                String[] userSplit = extractIncludes(cleanCode);
                programSource = BARISTA_PREAMBLE + userSplit[0] + userSplit[1];
                prefixLines = PREAMBLE_LINE_COUNT + countLines(userSplit[0]);
            } else {
                String[] split = extractIncludes(cleanCode);
                String userIncludes = split[0];
                String userBody     = split[1];

                StringBuilder globalDecls  = new StringBuilder();
                StringBuilder ctxStmts     = new StringBuilder();

                if (!depends.isEmpty()) {
                    Set<String> visited = new LinkedHashSet<>();
                    List<String> allDeps = resolveTransitiveDeps(depends, anchors, visited);
                    for (String dep : allDeps) {
                        String depClean = stripAnnotations(anchors.get(dep));
                        String[] depParts = extractIncludes(depClean);
                        String[] depSplit = splitDeclarationsAndStatements(depParts[1]);
                        if (!depSplit[0].isBlank()) globalDecls.append(depSplit[0]).append("\n");
                        if (!depSplit[1].isBlank()) ctxStmts.append(depSplit[1]);
                    }
                }

                String[] userSplit = splitDeclarationsAndStatements(userBody);
                String userDecls = userSplit[0];
                String userStmts = userSplit[1];

                StringBuilder prog = new StringBuilder();
                prog.append(BARISTA_PREAMBLE);
                prog.append(userIncludes);
                prog.append(globalDecls);
                prog.append(userDecls);
                if (!userDecls.isBlank() && !userDecls.endsWith("\n\n")) prog.append("\n");

                prog.append("int main() {\n");
                if (!ctxStmts.toString().isBlank()) {
                    prog.append("    __BaristaNullBuf __barista_null_buf;\n");
                    prog.append("    streambuf* __barista_old_buf = cout.rdbuf(&__barista_null_buf);\n");
                    prog.append(indent(ctxStmts.toString(), "    "));
                    prog.append("    cout.rdbuf(__barista_old_buf);\n");
                }
                prog.append(indent(userStmts, "    "));

                // Variable-dump trailer for the auto-wrap path. We only emit
                // scalar/string declarations our parser recognised — if the
                // compiler still rejects (e.g. shadowed name or non-ostreamable
                // template type) we retry without the trailer below.
                List<VariableInspector.CppDecl> cppDecls =
                        VariableInspector.parseCppStatementDeclarations(userStmts);
                String cppTrailer = VariableInspector.buildCppTrailer(cppDecls);
                if (!cppTrailer.isEmpty()) prog.append(cppTrailer);
                injectedTrailer = cppTrailer;

                prog.append("    return 0;\n");
                prog.append("}\n");

                programSource = prog.toString();
                prefixLines = PREAMBLE_LINE_COUNT + countLines(userIncludes) +
                              countLines(globalDecls.toString()) + countLines(userDecls) +
                              1; // int main() {
            }

            Files.writeString(sourceFile, programSource);

            // ── Compile ──────────────────────────────────────────────────────
            Process compileProc = startCompile(compiler, sourceFile, exeFile);

            StringBuilder compileStdout = new StringBuilder();
            StringBuilder compileStderr = new StringBuilder();
            Thread t1 = captureStream(compileProc.getInputStream(), compileStdout);
            Thread t2 = captureStream(compileProc.getErrorStream(), compileStderr);
            t1.start(); t2.start();

            boolean compiled = compileProc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            t1.join(2000); t2.join(2000);

            if (!compiled) {
                compileProc.destroyForcibly();
                return err(sessionId, cellId, "Compilation timed out.", start);
            }

            if (compileProc.exitValue() != 0) {
                // Did our injected variable-dump trailer cause this? Re-try once
                // without it so the user's actual code still compiles+runs.
                if (!injectedTrailer.isEmpty()) {
                    String noTrailer = programSource.replace(injectedTrailer, "");
                    Files.writeString(sourceFile, noTrailer);
                    Process retryProc = startCompile(compiler, sourceFile, exeFile);
                    StringBuilder rOut = new StringBuilder();
                    StringBuilder rErr = new StringBuilder();
                    Thread r1 = captureStream(retryProc.getInputStream(), rOut);
                    Thread r2 = captureStream(retryProc.getErrorStream(), rErr);
                    r1.start(); r2.start();
                    boolean rOk = retryProc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    r1.join(2000); r2.join(2000);
                    if (rOk && retryProc.exitValue() == 0) {
                        injectedTrailer = ""; // mark trailer absent for the run/parse step
                    } else {
                        String errMsg = compileStdout.toString() + compileStderr.toString();
                        errMsg = cleanPaths(errMsg, sourceFile);
                        errMsg = adjustLineNumbers(errMsg, prefixLines, compiler.type);
                        return ExecutionResult.builder()
                                .sessionId(sessionId).cellId(cellId)
                                .output("").error(errMsg.trim())
                                .status("COMPILE_ERROR").success(false)
                                .executionTimeMs(System.currentTimeMillis() - start)
                                .executionCount(execCounter.incrementAndGet())
                                .build();
                    }
                } else {
                    String errMsg = compileStdout.toString() + compileStderr.toString();
                    errMsg = cleanPaths(errMsg, sourceFile);
                    errMsg = adjustLineNumbers(errMsg, prefixLines, compiler.type);
                    return ExecutionResult.builder()
                            .sessionId(sessionId).cellId(cellId)
                            .output("").error(errMsg.trim())
                            .status("COMPILE_ERROR").success(false)
                            .executionTimeMs(System.currentTimeMillis() - start)
                            .executionCount(execCounter.incrementAndGet())
                            .build();
                }
            }

            // ── Run ──────────────────────────────────────────────────────────
            // Only the compiled program runs interactively; compilation above stays batch.
            ProcessBuilder runPb = new ProcessBuilder(exeFile.toAbsolutePath().toString());

            InteractiveProcessRunner.ProcRun run = runner.run(runPb);

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
            String runErrStr = run.stderr().trim();
            boolean success  = exitCode == 0 && runErrStr.isEmpty();

            if (success && anchor != null && !anchor.isBlank()) {
                anchors.put(anchor, code);
            }

            String errorOutput = runErrStr.isEmpty() && exitCode != 0
                    ? "Process exited with code " + exitCode
                    : runErrStr;

            // Strip the dump sentinels from stdout and attach the parsed vars.
            VariableInspector.ParsedOutput parsed =
                    VariableInspector.parseDumpFromOutput(run.stdout());

            return ExecutionResult.builder()
                    .sessionId(sessionId).cellId(cellId)
                    .output(parsed.cleanedStdout)
                    .error(errorOutput)
                    .status(success ? "OK" : "RUNTIME_ERROR")
                    .success(success)
                    .executionTimeMs(elapsed)
                    .executionCount(execCounter.incrementAndGet())
                    .localVariables(parsed.variables)
                    .build();

        } catch (Exception e) {
            log.error("C++ execution error in cell {}", cellId, e);
            return err(sessionId, cellId, e.getClass().getSimpleName() + ": " + e.getMessage(), start);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    public void clearSessionAnchors(String sessionId) {
        sessionAnchorSources.remove(sessionId);
    }

    public boolean isAvailable() {
        return findCompilerInfo() != null;
    }

    public String getCompilerDetail() {
        CompilerInfo ci = findCompilerInfo();
        if (ci == null) return "No C++ compiler found";
        if (ci.type == CompilerType.MSVC) return "MSVC (Visual Studio Build Tools)";
        try {
            Process p = new ProcessBuilder(ci.command, "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim().split("\n")[0];
            p.waitFor(5, TimeUnit.SECONDS);
            String label = ci.type == CompilerType.CLANGXX ? "clang++ " : "g++ ";
            return label + out.replaceFirst(".*?(\\d+\\.\\d+\\.\\d+).*", "$1");
        } catch (Exception e) {
            return ci.command;
        }
    }

    // ── Compiler detection ────────────────────────────────────────────────────

    private CompilerInfo findCompilerInfo() {
        // 1. Try g++ and clang++ on PATH first
        String[][] gccCandidates = {
            {"g++",       "GXX"},
            {"clang++",   "CLANGXX"},
            {"g++.exe",   "GXX"},
            {"clang++.exe", "CLANGXX"}
        };
        for (String[] c : gccCandidates) {
            try {
                Process p = new ProcessBuilder(c[0], "--version").start();
                p.getInputStream().transferTo(OutputStream.nullOutputStream());
                p.getErrorStream().transferTo(OutputStream.nullOutputStream());
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    CompilerType type = "CLANGXX".equals(c[1]) ? CompilerType.CLANGXX : CompilerType.GXX;
                    return new CompilerInfo(type, c[0]);
                }
            } catch (Exception ignored) {}
        }

        // 2. On Windows, search for Visual Studio MSVC
        if (isWindows()) {
            String vcvars = findVcVars64();
            if (vcvars != null) {
                log.info("Found MSVC compiler via {}", vcvars);
                return new CompilerInfo(CompilerType.MSVC, vcvars);
            }
        }

        return null;
    }

    /** Search known Visual Studio install paths for vcvars64.bat */
    private String findVcVars64() {
        List<String> roots = new ArrayList<>();
        String pf86 = System.getenv("ProgramFiles(x86)");
        String pf   = System.getenv("ProgramFiles");
        if (pf86 != null) roots.add(pf86);
        if (pf   != null) roots.add(pf);
        roots.add("C:\\Program Files (x86)");
        roots.add("C:\\Program Files");

        String[] versions = {"2022", "2019", "2017"};
        String[] editions = {"BuildTools", "Enterprise", "Professional", "Community", "Preview"};

        for (String root : roots) {
            for (String ver : versions) {
                for (String ed : editions) {
                    Path bat = Paths.get(root, "Microsoft Visual Studio", ver, ed,
                        "VC", "Auxiliary", "Build", "vcvars64.bat");
                    if (Files.exists(bat)) return bat.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    /** Start the compilation process, handling MSVC via a batch wrapper. */
    private Process startCompile(CompilerInfo compiler, Path src, Path out) throws IOException {
        if (compiler.type == CompilerType.MSVC) {
            return startMsvcCompile(compiler.command, src, out);
        }
        List<String> cmd = buildGccCommand(compiler.command, src, out);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    /** Write a .bat trampoline that activates MSVC environment then compiles. */
    private Process startMsvcCompile(String vcvars64, Path src, Path out) throws IOException {
        Path batFile = src.getParent().resolve("compile.bat");
        String bat =
            "@echo off\r\n" +
            "call \"" + vcvars64 + "\" >nul 2>&1\r\n" +
            "cl.exe /EHsc /std:c++17 /Zc:__cplusplus /utf-8 /nologo /W3 " +
            "/Fe\"" + out.toAbsolutePath() + "\" " +
            "\"" + src.toAbsolutePath() + "\"\r\n" +
            "exit /b %ERRORLEVEL%\r\n";
        Files.writeString(batFile, bat);
        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", batFile.toAbsolutePath().toString());
        pb.directory(src.getParent().toFile());
        pb.redirectErrorStream(false);
        return pb.start();
    }

    private List<String> buildGccCommand(String compiler, Path src, Path out) {
        List<String> cmd = new ArrayList<>();
        cmd.add(compiler);
        cmd.add("-std=c++17");
        cmd.add("-Wall");
        cmd.add("-Wno-unused-variable");
        cmd.add("-Wno-unused-but-set-variable");
        cmd.add("-o");
        cmd.add(out.toAbsolutePath().toString());
        cmd.add(src.toAbsolutePath().toString());
        return cmd;
    }

    private String buildNotFoundMessage() {
        if (isWindows()) {
            return "C++ compiler not found.\n\n" +
                "Arima supports three options on Windows — install any one:\n\n" +
                "Option A — MSYS2 + MinGW-w64 (recommended, free):\n" +
                "  1. Download and install MSYS2 from https://www.msys2.org/\n" +
                "  2. Open the MSYS2 UCRT64 terminal and run:\n" +
                "       pacman -S mingw-w64-ucrt-x86_64-gcc\n" +
                "  3. Add C:\\msys64\\ucrt64\\bin to your Windows PATH\n" +
                "     (Start → Edit the system environment variables → Environment Variables\n" +
                "      → System variables → Path → Edit → New → C:\\msys64\\ucrt64\\bin)\n" +
                "  4. Restart Arima\n\n" +
                "Option B — WinLibs (standalone, no installer):\n" +
                "  1. Download from https://winlibs.com/ (choose UCRT64, Win64)\n" +
                "  2. Extract to C:\\mingw64\n" +
                "  3. Add C:\\mingw64\\bin to your PATH (same steps as above)\n" +
                "  4. Restart Arima\n\n" +
                "Option C — Chocolatey (run as Administrator):\n" +
                "  choco install mingw\n" +
                "  Then restart Arima.\n\n" +
                "Option D — Visual Studio Build Tools (already installed check):\n" +
                "  If you have Visual Studio 2017/2019/2022, Arima will detect it\n" +
                "  automatically. Make sure 'Desktop development with C++' is installed.\n" +
                "  Open Visual Studio Installer → Modify → Desktop development with C++.\n\n" +
                "After installing, verify in a new terminal: g++ --version\n" +
                "Then restart Arima (Settings → Restart Server).";
        }
        return "C++ compiler not found.\n\n" +
            "Install g++ or clang++:\n" +
            "  Ubuntu/Debian:  sudo apt update && sudo apt install build-essential\n" +
            "  Fedora/RHEL:    sudo dnf install gcc-c++\n" +
            "  Arch/Manjaro:   sudo pacman -S gcc\n" +
            "  macOS:          xcode-select --install\n\n" +
            "Verify: g++ --version  (should show 9.0 or higher)\n" +
            "Then restart Arima.";
    }

    // ── Source splitting ──────────────────────────────────────────────────────

    String[] splitDeclarationsAndStatements(String code) {
        if (code == null || code.isBlank()) return new String[]{"", ""};

        StringBuilder decls = new StringBuilder();
        StringBuilder stmts = new StringBuilder();

        String[] lines = code.split("\n", -1);
        int depth = 0;
        List<String> block = new ArrayList<>();
        boolean blockIsDecl = false;
        // Buffer template<> header lines so they stay attached to the next declaration
        List<String> templatePrefix = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            int opens  = countBraces(line, '{');
            int closes = countBraces(line, '}');

            if (depth == 0) {
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    if (!block.isEmpty()) block.add(line);
                    else stmts.append(line).append("\n");
                    continue;
                }

                // Buffer template header lines (e.g. "template<typename T>") — they have no
                // braces and must be emitted together with the following declaration.
                if (opens == 0 && startsWithKeyword(trimmed, "template") && !trimmed.endsWith(";")) {
                    templatePrefix.add(line);
                    continue;
                }

                int newDepth = depth + opens - closes;

                if (opens > 0 && newDepth > 0) {
                    // Multi-line block: classify by whether a template prefix is present OR
                    // the opening line looks like a function/class definition.
                    blockIsDecl = !templatePrefix.isEmpty() || looksLikeDeclaration(trimmed);
                    for (String tp : templatePrefix) block.add(tp);
                    templatePrefix.clear();
                    block.add(line);
                    depth = newDepth;
                } else if (opens > 0) {
                    // Single-line with balanced braces: e.g. void foo() { return x; }
                    boolean isDecl = !templatePrefix.isEmpty() || looksLikeDeclaration(trimmed);
                    if (isDecl) {
                        for (String tp : templatePrefix) decls.append(tp).append("\n");
                        decls.append(line).append("\n");
                    } else {
                        for (String tp : templatePrefix) stmts.append(tp).append("\n");
                        stmts.append(line).append("\n");
                    }
                    templatePrefix.clear();
                    depth = Math.max(0, newDepth);
                } else {
                    // No opening brace — check for declarations that don't need braces:
                    // "using X = ...;", "typedef ...;", namespace aliases, etc.
                    if (!templatePrefix.isEmpty()) {
                        // Template header followed by a no-brace line (rare — forward decl)
                        for (String tp : templatePrefix) decls.append(tp).append("\n");
                        templatePrefix.clear();
                        decls.append(line).append("\n");
                    } else if (DECL_START_RE.matcher(trimmed).find()) {
                        decls.append(line).append("\n");
                    } else {
                        stmts.append(line).append("\n");
                    }
                    depth = Math.max(0, newDepth);
                }
            } else {
                block.add(line);
                depth = Math.max(0, depth + opens - closes);
                if (depth == 0) {
                    String joined = String.join("\n", block) + "\n";
                    if (blockIsDecl) decls.append(joined);
                    else stmts.append(joined);
                    block.clear();
                    blockIsDecl = false;
                }
            }
        }

        // Flush orphaned template prefix (shouldn't normally happen)
        for (String tp : templatePrefix) decls.append(tp).append("\n");
        if (!block.isEmpty()) {
            decls.append(String.join("\n", block)).append("\n");
        }

        return new String[]{decls.toString(), stmts.toString()};
    }

    private String[] extractIncludes(String code) {
        StringBuilder includes = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        boolean inIncludes = true;

        for (String line : code.split("\n", -1)) {
            String t = line.trim();
            if (inIncludes && (t.startsWith("#include") || t.startsWith("#pragma") ||
                    t.startsWith("#define") || t.startsWith("#ifndef") ||
                    t.startsWith("#ifdef") || t.startsWith("#endif") || t.isEmpty())) {
                includes.append(line).append("\n");
            } else {
                inIncludes = false;
                rest.append(line).append("\n");
            }
        }
        return new String[]{includes.toString(), rest.toString()};
    }

    private static boolean startsWithKeyword(String s, String kw) {
        if (!s.startsWith(kw)) return false;
        if (s.length() == kw.length()) return true;
        char c = s.charAt(kw.length());
        return !Character.isLetterOrDigit(c) && c != '_';
    }

    private boolean looksLikeDeclaration(String trimmed) {
        if (DECL_START_RE.matcher(trimmed).find()) return true;
        // Word-boundary keyword checks: "double" must NOT match "do", "ifstream" must NOT match "if"
        if (startsWithKeyword(trimmed, "if") || startsWithKeyword(trimmed, "else") ||
            startsWithKeyword(trimmed, "for") || startsWithKeyword(trimmed, "while") ||
            startsWithKeyword(trimmed, "switch") || startsWithKeyword(trimmed, "do") ||
            startsWithKeyword(trimmed, "try") || startsWithKeyword(trimmed, "catch") ||
            startsWithKeyword(trimmed, "return") || startsWithKeyword(trimmed, "throw") ||
            startsWithKeyword(trimmed, "break") || startsWithKeyword(trimmed, "continue") ||
            trimmed.startsWith("{") || trimmed.startsWith("}") ||
            trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return false;
        }
        // Strip inline comment to avoid matching parens inside comments
        String code = trimmed;
        int commentAt = trimmed.indexOf("//");
        if (commentAt >= 0) code = trimmed.substring(0, commentAt).trim();

        if (code.endsWith(";") || code.isEmpty()) return false;

        int parenIdx = code.indexOf('(');
        if (parenIdx > 0) {
            // If the first argument is a lambda capture '[', this is a variable with a
            // constructor/lambda arg — not a function definition.
            String afterParen = code.substring(parenIdx + 1).stripLeading();
            if (!afterParen.isEmpty() && afterParen.charAt(0) == '[') return false;

            String beforeParen = code.substring(0, parenIdx).trim();

            // If beforeParen contains '=' it's an assignment expression, not a function signature.
            if (beforeParen.contains("=")) return false;

            // Match "ReturnType FuncName" — return type may include template angle brackets,
            // pointers, or references: "double area", "optional<int> parsePositive", "int* get"
            return beforeParen.matches(".*\\s[a-zA-Z_]\\w*") ||
                   beforeParen.matches("[~]?\\w[\\w:~<>*&]*"); // constructor / destructor
        }
        return false;
    }

    // ── Annotation parsing ────────────────────────────────────────────────────

    private String parseAnchor(String code) {
        Matcher m = ANCHOR_RE.matcher(code);
        return m.find() ? m.group(1).trim() : null;
    }

    private List<String> parseDepends(String code) {
        Matcher m = DEPENDS_RE.matcher(code);
        if (!m.find()) return Collections.emptyList();
        return Arrays.stream(m.group(1).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String stripAnnotations(String code) {
        return code.replaceAll("(?m)^//@ *(anchor|depends|namespace|pipeline|steps):.*$", "").trim();
    }

    // ── Transitive dependency resolution ──────────────────────────────────────

    private List<String> resolveTransitiveDeps(List<String> deps, Map<String, String> anchors,
                                                Set<String> visited) {
        List<String> result = new ArrayList<>();
        for (String dep : deps) {
            if (visited.contains(dep)) continue;
            visited.add(dep);
            String depSrc = anchors.get(dep);
            if (depSrc != null) {
                List<String> transitive = parseDepends(depSrc);
                result.addAll(resolveTransitiveDeps(transitive, anchors, visited));
            }
            result.add(dep);
        }
        return result;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private boolean hasMainFunction(String code) {
        return code.contains("int main(") || code.contains("int main (");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Adjust compiler error line numbers to account for the injected preamble. */
    private String adjustLineNumbers(String error, int offset, CompilerType type) {
        if (error == null || error.isBlank() || offset <= 0) return error;
        Pattern p;
        String replacement;
        if (type == CompilerType.MSVC) {
            // MSVC: main.cpp(42): error C2065: ...
            p = Pattern.compile("main\\.cpp\\((\\d+)\\)");
            Matcher m = p.matcher(error);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int adjusted = Math.max(1, Integer.parseInt(m.group(1)) - offset);
                m.appendReplacement(sb, "main.cpp(" + adjusted + ")");
            }
            m.appendTail(sb);
            return sb.toString();
        } else {
            // g++/clang++: main.cpp:42:5: error: ...
            p = Pattern.compile("main\\.cpp:(\\d+)");
            Matcher m = p.matcher(error);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int adjusted = Math.max(1, Integer.parseInt(m.group(1)) - offset);
                m.appendReplacement(sb, "main.cpp:" + adjusted);
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    private String cleanPaths(String text, Path sourceFile) {
        String abs = sourceFile.toAbsolutePath().toString();
        String rel = sourceFile.toString();
        // Also handle forward-slash variants (MSVC on Windows may use either)
        return text.replace(abs, "main.cpp")
                   .replace(abs.replace('\\', '/'), "main.cpp")
                   .replace(rel, "main.cpp")
                   .replace(rel.replace('\\', '/'), "main.cpp");
    }

    private String indent(String code, String prefix) {
        if (code == null || code.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (line.isBlank()) sb.append("\n");
            else sb.append(prefix).append(line).append("\n");
        }
        return sb.toString();
    }

    private int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (int) s.chars().filter(c -> c == '\n').count();
    }

    private int countBraces(String line, char brace) {
        int count = 0;
        boolean inStr = false;
        boolean inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !inChar) inStr = !inStr;
            else if (c == '\'' && !inStr) inChar = !inChar;
            else if (!inStr && !inChar) {
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
                if (c == brace) count++;
            }
        }
        return count;
    }

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
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {}
    }
}
