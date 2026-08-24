package com.barista.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.barista.model.PyPiPackageInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Manages PyPI package installation for Python cells.
 *
 * <p>Packages are installed with {@code python -m pip install --target data/pypi-packages/site},
 * an isolated directory added to {@code PYTHONPATH} when Python cells run — so users can
 * {@code import <package>} directly without touching the system site-packages.</p>
 *
 * <p>Because pip's {@code uninstall} does not support {@code --target}, each install records the
 * top-level entries it added under the site directory (a before/after diff), and uninstall deletes
 * exactly those. Package metadata is persisted in {@code data/pypi-packages.json}.</p>
 *
 * <p>"Search" uses the canonical PyPI JSON API ({@code https://pypi.org/pypi/<name>/json}) — an
 * exact-name lookup that returns the package's latest version and summary. Install accepts any
 * name, so anything on PyPI can be installed whether or not the lookup returns a card.</p>
 */
@Service
public class PyPiService {

    private static final Logger log = LoggerFactory.getLogger(PyPiService.class);
    private static final String PYPI_JSON = "https://pypi.org/pypi/";

    @Value("${barista.data.dir:data}")
    private String dataDir;

    /** STOMP topic pip output streams to; the PyPI tab subscribes to this session. */
    public static final String INSTALL_SESSION = "pypi-install";

    private final PythonExecutionService python;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private List<PyPiPackageInfo> installedPackages = new ArrayList<>();

    public PyPiService(PythonExecutionService python, SimpMessagingTemplate messaging) {
        this.python = python;
        this.messaging = messaging;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.httpClient = HttpClient.newHttpClient();
    }

    /** Push a line of install output to the browser (shell-style live log). */
    private void stream(String text) {
        try {
            messaging.convertAndSend("/topic/shell/" + INSTALL_SESSION,
                    Map.of("type", "partial_output", "cellId", "__pypi__", "text", text));
        } catch (Exception ignore) {}
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(sitePath());
        installedPackages = loadPackageList();
    }

    public List<PyPiPackageInfo> getInstalledPackages() {
        return Collections.unmodifiableList(installedPackages);
    }

    /** Absolute path added to PYTHONPATH for Python cells. */
    public Path sitePath() {
        return Paths.get(dataDir, "pypi-packages", "site").toAbsolutePath();
    }

    /** Interpreter availability + version, for the UI status line. */
    public Map<String, Object> pythonStatus() {
        return Map.of(
            "available", python.isPythonAvailable(),
            "version", python.pythonVersion()
        );
    }

    /** Install (or upgrade) a PyPI package into the target site directory. */
    public PyPiPackageInfo installPackage(String name, String version) throws IOException, InterruptedException {
        List<String> py = python.pythonCommand();
        if (py == null) throw new IOException("Python not found. Install Python 3.8+ and restart Arima.");

        String resolved = (version == null || version.isBlank() || "latest".equalsIgnoreCase(version.trim()))
                ? null : version.trim();
        String spec = resolved == null ? name.trim() : name.trim() + "==" + resolved;

        Path site = sitePath();
        Files.createDirectories(site);

        // For an upgrade, remove the previous install's files first so the diff is clean.
        installedPackages.stream().filter(p -> p.getName().equalsIgnoreCase(name.trim()))
                .findFirst().ifPresent(prev -> deletePaths(prev.getPaths()));

        Set<String> before = listSiteEntries();

        log.info("Installing PyPI package: {}", spec);
        List<String> cmd = new ArrayList<>(py);
        cmd.addAll(List.of("-m", "pip", "install", "--target", site.toString(),
                "--no-input", "--disable-pip-version-check", "--progress-bar", "off", "--upgrade", spec));
        stream("$ pip install " + spec + "\n");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                output.append(line).append('\n');
                stream(line + "\n");   // live, shell-style
            }
        }
        // Big packages (pandas, numpy, scikit-learn) can pull large wheels — allow up to 10 min.
        if (!process.waitFor(600, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            stream("✗ pip install timed out after 10 minutes.\n");
            throw new IOException("pip install timed out for: " + spec + " (10 min). Try again or check your connection.");
        }
        if (process.exitValue() != 0) {
            String msg = output.toString().trim();
            if (msg.contains("No module named pip")) {
                msg = "pip is not available for this interpreter. Run:  " + String.join(" ", py) +
                      " -m ensurepip --upgrade\n\n" + msg;
            }
            stream("✗ install failed (exit " + process.exitValue() + ").\n");
            throw new IOException("pip install failed for " + spec + ":\n" + msg);
        }

        Set<String> after = listSiteEntries();
        after.removeAll(before);
        List<String> newPaths = new ArrayList<>(after);

        String actualVersion = resolveInstalledVersion(name.trim(), newPaths, resolved);

        installedPackages.removeIf(p -> p.getName().equalsIgnoreCase(name.trim()));
        PyPiPackageInfo pkg = new PyPiPackageInfo(name.trim(), actualVersion, LocalDateTime.now(), newPaths);
        installedPackages.add(pkg);
        savePackageList();
        stream("✓ Installed " + name.trim() + " " + actualVersion + " — import it in a Python cell.\n");
        log.info("Installed PyPI package: {}=={}", name, actualVersion);
        return pkg;
    }

    /** Remove an installed PyPI package (deletes exactly the files its install added). */
    public boolean removePackage(String name) {
        Optional<PyPiPackageInfo> existing = installedPackages.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name)).findFirst();
        if (existing.isEmpty()) return false;
        deletePaths(existing.get().getPaths());
        installedPackages.removeIf(p -> p.getName().equalsIgnoreCase(name));
        savePackageList();
        log.info("Removed PyPI package: {}", name);
        return true;
    }

    /**
     * Look up a package on PyPI by exact name. Returns a one-element list ({name, version,
     * description}) if it exists, or empty if not found.
     */
    public List<Map<String, String>> searchPackages(String query) throws IOException, InterruptedException {
        String url = PYPI_JSON + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8) + "/json";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return List.of();
        if (response.statusCode() != 200) throw new IOException("PyPI lookup failed: " + response.statusCode());

        JsonNode info = objectMapper.readTree(response.body()).path("info");
        Map<String, String> item = new LinkedHashMap<>();
        item.put("name", info.path("name").asText(query));
        item.put("version", info.path("version").asText(""));
        String summary = info.path("summary").asText("");
        item.put("description", summary == null ? "" : summary);
        return List.of(item);
    }

    // ── internals ───────────────────────────────────────────────────────────

    private Set<String> listSiteEntries() {
        Set<String> names = new HashSet<>();
        Path site = sitePath();
        if (!Files.exists(site)) return names;
        try (Stream<Path> s = Files.list(site)) {
            s.forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException e) {
            log.warn("Could not list site dir: {}", e.getMessage());
        }
        return names;
    }

    private void deletePaths(List<String> paths) {
        if (paths == null) return;
        Path site = sitePath();
        for (String entry : paths) {
            Path target = site.resolve(entry);
            if (!target.normalize().startsWith(site)) continue; // guard against traversal
            try {
                if (Files.isDirectory(target)) {
                    try (Stream<Path> walk = Files.walk(target)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                    }
                } else {
                    Files.deleteIfExists(target);
                }
            } catch (IOException e) {
                log.warn("Could not delete {}: {}", target, e.getMessage());
            }
        }
    }

    /** Read the version from the installed {@code <name>-<ver>.dist-info} directory. */
    private String resolveInstalledVersion(String name, List<String> newPaths, String requested) {
        // PyPI normalizes names case-insensitively with -/_ treated alike.
        String want = name.toLowerCase().replace('_', '-');
        for (String entry : newPaths) {
            if (!entry.toLowerCase().endsWith(".dist-info")) continue;
            String base = entry.substring(0, entry.length() - ".dist-info".length()); // e.g. six-1.17.0
            int dash = base.lastIndexOf('-');
            if (dash <= 0 || dash >= base.length() - 1) continue;
            String distName = base.substring(0, dash).toLowerCase().replace('_', '-');
            if (distName.equals(want)) return base.substring(dash + 1);
        }
        return requested != null ? requested : "latest";
    }

    private List<PyPiPackageInfo> loadPackageList() {
        Path path = Paths.get(dataDir, "pypi-packages.json");
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<List<PyPiPackageInfo>>() {});
        } catch (IOException e) {
            log.warn("Failed to load PyPI package list: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void savePackageList() {
        Path path = Paths.get(dataDir, "pypi-packages.json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), installedPackages);
        } catch (IOException e) {
            log.error("Failed to save PyPI package list: {}", e.getMessage());
        }
    }
}
