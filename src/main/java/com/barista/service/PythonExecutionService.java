package com.barista.service;

import com.barista.model.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Python execution service using the system Python 3 interpreter.
 *
 * <p>Each cell runs in a fresh {@code python} subprocess (same model as
 * {@link NodeJsExecutionService}). Packages installed via the PyPI tab live under
 * {@code data/pypi-packages/site} and are exposed on {@code PYTHONPATH}, so users can
 * {@code import requests} (etc.) directly.</p>
 *
 * <h3>Pipeline dependency injection</h3>
 * Like the C#/F# services, Python cells run isolated — no shared REPL state. To support
 * {@code //@ depends:} annotations, this service keeps a per-session anchor-source cache.
 * When a cell with {@code //@ anchor: name} succeeds, its source is stored; when a later
 * cell declares {@code //@ depends: name}, the transitive closure of ancestor sources is
 * written to a side file and {@code exec()}'d into the cell's globals with stdout suppressed
 * (so only the current cell's output shows) before the cell's own code runs.
 *
 * <h3>Arima helpers pre-injected into every cell</h3>
 * <pre>
 *   barista.table(rows)    # pretty-print a list of dicts as a text table
 *   barista.display(obj)   # json.dumps with indent (falls back to print)
 *   barista.html(content)  # render inline HTML in the cell output
 *   barista.stats(values)  # count / min / max / mean / std summary
 * </pre>
 *
 * Prerequisites: Python 3.8+ on the PATH ({@code python}, {@code python3}, or the Windows
 * {@code py} launcher). Package install/uninstall lives in {@link PyPiService}.
 */
@Service
public class PythonExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PythonExecutionService.class);

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Arima helper preamble injected at the top of every Python cell. */
    private static final String BARISTA_PREAMBLE =
        "import sys as _sys, json as _json\n" +
        "class _Barista:\n" +
        "    def table(self, rows):\n" +
        "        rows = list(rows) if rows is not None else []\n" +
        "        if not rows:\n" +
        "            print('(empty)'); return\n" +
        "        keys = list(rows[0].keys())\n" +
        "        widths = [max([len(str(k))] + [len(str(r.get(k, ''))) for r in rows]) for k in keys]\n" +
        "        sep = '+' + '+'.join('-' * (w + 2) for w in widths) + '+'\n" +
        "        def _fmt(vals): return '|' + '|'.join(' ' + str(v).ljust(widths[i]) + ' ' for i, v in enumerate(vals)) + '|'\n" +
        "        print(sep); print(_fmt(keys)); print(sep)\n" +
        "        for r in rows: print(_fmt([r.get(k, '') for k in keys]))\n" +
        "        print(sep); print(f'{len(rows)} row(s)')\n" +
        "    def display(self, obj):\n" +
        "        try: print(_json.dumps(obj, indent=2, default=str))\n" +
        "        except Exception: print(obj)\n" +
        "    def html(self, content):\n" +
        "        print('BARISTA_HTML:' + str(content).replace('\\n', ' '))\n" +
        "    def image(self, data, mime='image/png'):\n" +
        "        import base64 as _b64, io as _io\n" +
        "        if hasattr(data, 'savefig'):\n" +
        "            buf = _io.BytesIO(); data.savefig(buf, format='png', bbox_inches='tight'); raw = buf.getvalue()\n" +
        "        elif hasattr(data, 'read'):\n" +
        "            raw = data.read()\n" +
        "        elif isinstance(data, (bytes, bytearray)):\n" +
        "            raw = bytes(data)\n" +
        "        else:\n" +
        "            with open(data, 'rb') as _f: raw = _f.read()\n" +
        "        b64 = _b64.b64encode(raw).decode()\n" +
        "        print('BARISTA_HTML:<img alt=\"chart\" style=\"max-width:100%\" src=\"data:' + mime + ';base64,' + b64 + '\">')\n" +
        "    def stats(self, values):\n" +
        "        vals = list(values)\n" +
        "        if not vals:\n" +
        "            print('(empty)'); return\n" +
        "        n = len(vals); s = sorted(vals); total = sum(vals); mean = total / n\n" +
        "        var = sum((x - mean) ** 2 for x in vals) / n\n" +
        "        print(f'count: {n}  min: {s[0]}  max: {s[-1]}  mean: {mean:.4f}  std: {var ** 0.5:.4f}')\n" +
        "barista = _Barista()\n";

    private static final int PREAMBLE_LINES = countNewlines(BARISTA_PREAMBLE);

    @Value("${barista.data.dir:data}")
    private String dataDir;

    private final InteractiveProcessRunner runner;
    private final AtomicInteger execCounter = new AtomicInteger(0);

    /** sessionId → (anchorName → original cell source, annotations intact). */
    private final Map<String, Map<String, String>> sessionAnchorSources = new ConcurrentHashMap<>();

    /** Cached interpreter command prefix (e.g. ["python"] or ["py","-3"]); null until resolved. */
    private volatile List<String> pythonCmd;

    public PythonExecutionService(InteractiveProcessRunner runner) {
        this.runner = runner;
    }

    /** Execute Python code. */
    public ExecutionResult execute(String sessionId, String cellId, String code) {
        long start = System.currentTimeMillis();

        List<String> py = resolvePython();
        if (py == null) {
            return err(sessionId, cellId,
                "Python not found.\n\n" +
                "Quick setup — run one of these from the Arima folder:\n" +
                "  Windows:      ./scripts/setup-python.ps1\n" +
                "  Linux/macOS:  ./scripts/setup-python.sh\n\n" +
                "Or install Python 3.8+ from https://www.python.org/downloads/ (tick 'Add to PATH'),\n" +
                "ensure 'python' (or 'python3') is on your PATH, then restart Arima.", start);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("barista-py-");
            Path scriptFile = tempDir.resolve("script.py");

            String anchor = parseAnchor(code);
            List<String> depends = parseDepends(code);
            String cleanCode = stripLeadingAnnotations(code);

            Map<String, String> anchors = sessionAnchorSources
                    .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

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

            StringBuilder script = new StringBuilder(BARISTA_PREAMBLE);
            int prefixLines = PREAMBLE_LINES;

            // Inject ancestor code (transitive), exec'd with stdout suppressed.
            if (!depends.isEmpty()) {
                Set<String> visited = new LinkedHashSet<>();
                List<String> allDeps = resolveTransitiveDeps(depends, anchors, visited);
                StringBuilder ctx = new StringBuilder();
                for (String dep : allDeps) {
                    ctx.append(stripLeadingAnnotations(anchors.get(dep))).append('\n');
                }
                if (!ctx.toString().isBlank()) {
                    Path ctxFile = tempDir.resolve("_ctx.py");
                    Files.writeString(ctxFile, ctx.toString());
                    // 3 lines added before user code — keep in sync with prefixLines bump.
                    script.append("import contextlib as _ctx, os as _os\n");
                    script.append("with _ctx.redirect_stdout(open(_os.devnull, 'w')):\n");
                    script.append("    exec(compile(open(r'").append(ctxFile.toString())
                          .append("', encoding='utf-8').read(), '<ancestors>', 'exec'), globals())\n");
                    prefixLines += 3;
                }
            }

            script.append(cleanCode);
            Files.writeString(scriptFile, script.toString());

            Path site = Paths.get(dataDir, "pypi-packages", "site").toAbsolutePath();

            List<String> cmd = new ArrayList<>(py);
            cmd.add("-u");            // unbuffered → live streaming
            cmd.add("-X"); cmd.add("utf8");
            cmd.add(scriptFile.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Expose installed PyPI packages via PYTHONPATH.
            String existing = pb.environment().getOrDefault("PYTHONPATH", "");
            String sep = IS_WINDOWS ? ";" : ":";
            pb.environment().put("PYTHONPATH",
                    existing.isBlank() ? site.toString() : site + sep + existing);
            pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");

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

            String cleanErr = run.stderr().trim()
                    .replace(scriptFile.toString(), "script.py")
                    .replace(tempDir.toString() + File.separator, "")
                    .replace(tempDir.toString(), "");
            cleanErr = shiftLineNumbers(cleanErr, prefixLines);

            boolean success = exitCode == 0;

            // On success, cache source so dependent cells can inject it.
            if (success && anchor != null && !anchor.isBlank()) {
                anchors.put(anchor, code);
            }

            return ExecutionResult.builder()
                    .sessionId(sessionId).cellId(cellId)
                    .output(run.stdout())
                    .error(success ? "" : cleanErr)
                    .status(success ? "OK" : "RUNTIME_ERROR")
                    .success(success)
                    .executionTimeMs(elapsed)
                    .executionCount(execCounter.incrementAndGet())
                    .build();

        } catch (Exception e) {
            log.error("Python execution error in cell {}", cellId, e);
            return err(sessionId, cellId, e.getClass().getSimpleName() + ": " + e.getMessage(), start);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    /** True if a Python 3 interpreter is available. */
    public boolean isPythonAvailable() {
        return resolvePython() != null;
    }

    /** The resolved interpreter command prefix, e.g. ["python"] or ["py","-3"] (or null). */
    public List<String> pythonCommand() {
        List<String> c = resolvePython();
        return c == null ? null : new ArrayList<>(c);
    }

    /** Reported interpreter version string, or "" if unavailable. */
    public String pythonVersion() {
        List<String> py = resolvePython();
        if (py == null) return "";
        try {
            List<String> cmd = new ArrayList<>(py);
            cmd.add("--version");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return out; // e.g. "Python 3.12.4"
        } catch (Exception e) {
            return "";
        }
    }

    /** Clear the anchor cache for a session (call on kernel restart). */
    public void clearSessionAnchors(String sessionId) {
        sessionAnchorSources.remove(sessionId);
    }

    /** Pre-seed an anchor source (used by cross-notebook module loading). */
    public void cacheAnchorSource(String sessionId, String key, String source) {
        sessionAnchorSources.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                            .put(key, source);
    }

    // ── interpreter resolution ──────────────────────────────────────────────

    private List<String> resolvePython() {
        List<String> cached = pythonCmd;
        if (cached != null) return cached.isEmpty() ? null : cached;

        List<List<String>> candidates = IS_WINDOWS
                ? List.of(List.of("python"), List.of("py", "-3"), List.of("python3"))
                : List.of(List.of("python3"), List.of("python"));
        for (List<String> cand : candidates) {
            if (worksAsPython3(cand)) { pythonCmd = cand; return cand; }
        }
        pythonCmd = List.of(); // negative cache
        return null;
    }

    private boolean worksAsPython3(List<String> cmd) {
        try {
            List<String> full = new ArrayList<>(cmd);
            full.add("--version");
            Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            return ok && out.contains("Python 3");
        } catch (Exception e) {
            return false;
        }
    }

    // ── annotation helpers (mirrors DotNetExecutionService) ─────────────────

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

    private List<String> parseDepends(String code) {
        if (code == null) return List.of();
        for (String line : code.split("\n", -1)) {
            String t = line.strip();
            if (!t.startsWith("//@")) break;
            String rest = t.substring(3).strip();
            if (rest.startsWith("depends:")) {
                return Arrays.stream(rest.substring(8).split(","))
                             .map(String::strip).filter(s -> !s.isEmpty())
                             .collect(Collectors.toList());
            }
        }
        return List.of();
    }

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

    private List<String> resolveTransitiveDeps(List<String> direct,
                                               Map<String, String> sources, Set<String> visited) {
        List<String> result = new ArrayList<>();
        for (String dep : direct) {
            if (!visited.add(dep)) continue;
            String src = sources.get(dep);
            if (src != null) result.addAll(resolveTransitiveDeps(parseDepends(src), sources, visited));
            result.add(dep);
        }
        return result;
    }

    /** Subtract injected-prefix lines from {@code File "script.py", line N} references. */
    private String shiftLineNumbers(String error, int offset) {
        if (error == null || error.isEmpty() || offset <= 0) return error;
        Pattern p = Pattern.compile("(File \"script\\.py\", line )(\\d+)");
        Matcher m = p.matcher(error);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int adjusted = Math.max(1, Integer.parseInt(m.group(2)) - offset);
            m.appendReplacement(sb, m.group(1) + adjusted);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
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
