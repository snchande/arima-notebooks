package com.barista.service;

import com.barista.shell.JShellManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Assembles the live server metadata reported by {@code GET /api/system/info}.
 *
 * The CLI launchers (arima.cmd / arima.ps1 / arima.sh) call this on a bare
 * invocation and on {@code arima status} to answer "is it up, since when, and
 * what is it running?" without the launcher having to guess from a PID file.
 */
@Service
public class ServerInfoService {

    private static final Logger log = LoggerFactory.getLogger(ServerInfoService.class);

    /** One-line description of the product, surfaced by the CLI and the UI. */
    public static final String TAGLINE =
        "A local-first, AI-native notebook for eight languages - run code, build pipelines, and drive it all over MCP.";

    private static final String MCP_PROTOCOL = "2024-11-05";
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final JShellManager jShellManager;
    private final NotebookService notebookService;
    private final TypeScriptExecutionService typeScriptExecutionService;
    private final DotNetExecutionService dotNetExecutionService;
    private final CppExecutionService cppExecutionService;
    private final PythonExecutionService pythonExecutionService;

    @Value("${barista.version:1.0.0-dev}")
    private String appVersion;

    @Value("${barista.build.timestamp:dev-build}")
    private String buildTimestamp;

    @Value("${server.port:8585}")
    private int port;

    @Value("${barista.auth.mode:local}")
    private String authMode;

    @Value("${barista.notebooks.dir:notebooks}")
    private String notebooksDir;

    public ServerInfoService(JShellManager jShellManager,
                             NotebookService notebookService,
                             TypeScriptExecutionService typeScriptExecutionService,
                             DotNetExecutionService dotNetExecutionService,
                             CppExecutionService cppExecutionService,
                             PythonExecutionService pythonExecutionService) {
        this.jShellManager = jShellManager;
        this.notebookService = notebookService;
        this.typeScriptExecutionService = typeScriptExecutionService;
        this.dotNetExecutionService = dotNetExecutionService;
        this.cppExecutionService = cppExecutionService;
        this.pythonExecutionService = pythonExecutionService;
    }

    public Map<String, Object> getInfo() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        long startedAtMs = runtime.getStartTime();
        long uptimeMs = runtime.getUptime();
        String url = "http://localhost:" + port;

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "Arima Notebooks");
        info.put("tagline", TAGLINE);
        info.put("status", "running");
        info.put("version", appVersion);
        info.put("buildTimestamp", buildTimestamp);
        info.put("startedAt", ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(startedAtMs), ZoneId.systemDefault()).format(STAMP));
        info.put("startedAtEpochMs", startedAtMs);
        info.put("uptimeMs", uptimeMs);
        info.put("uptime", humanizeUptime(uptimeMs));
        info.put("pid", runtime.getPid());
        info.put("port", port);
        info.put("url", url);
        info.put("authMode", authMode);
        info.put("java", javaInfo());
        info.put("os", osInfo());
        info.put("memory", memoryInfo());
        info.put("sessions", sessionInfo());
        info.put("notebooks", notebookInfo());
        info.put("mcp", mcpInfo(url));
        info.put("languages", languageInfo());
        return info;
    }

    static String humanizeUptime(long ms) {
        long totalSeconds = ms / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0)                 sb.append(days).append("d ");
        if (days > 0 || hours > 0)    sb.append(hours).append("h ");
        if (days > 0 || hours > 0 || minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString();
    }

    private Map<String, Object> javaInfo() {
        Map<String, Object> java = new LinkedHashMap<>();
        java.put("version", System.getProperty("java.version"));
        java.put("vendor", System.getProperty("java.vendor"));
        java.put("vm", System.getProperty("java.vm.name"));
        java.put("home", System.getProperty("java.home"));
        return java;
    }

    private Map<String, Object> osInfo() {
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("name", System.getProperty("os.name"));
        os.put("version", System.getProperty("os.version"));
        os.put("arch", System.getProperty("os.arch"));
        os.put("cpus", Runtime.getRuntime().availableProcessors());
        return os;
    }

    private Map<String, Object> memoryInfo() {
        Runtime rt = Runtime.getRuntime();
        long mb = 1024 * 1024;
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("usedMb", (rt.totalMemory() - rt.freeMemory()) / mb);
        mem.put("totalMb", rt.totalMemory() / mb);
        mem.put("maxMb", rt.maxMemory() / mb);
        return mem;
    }

    private Map<String, Object> sessionInfo() {
        Set<String> ids = jShellManager.getSessionIds();
        Map<String, Object> sessions = new LinkedHashMap<>();
        sessions.put("active", ids.size());
        sessions.put("ids", new ArrayList<>(ids));
        return sessions;
    }

    private Map<String, Object> notebookInfo() {
        Map<String, Object> notebooks = new LinkedHashMap<>();
        notebooks.put("tutorials", notebookService.listTutorials().size());
        notebooks.put("total", countNotebookFiles());
        notebooks.put("dir", notebooksDir);
        return notebooks;
    }

    /**
     * Counts every notebook file on disk rather than calling listNotebooks(userId):
     * this endpoint is server-wide, so it must not be scoped to one user.
     */
    private long countNotebookFiles() {
        Path root = Paths.get(notebooksDir);
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                        .filter(p -> { String n = p.getFileName().toString();
                                       return n.endsWith(".anb") || n.endsWith(".vnb"); })
                        .count();
        } catch (IOException e) {
            log.debug("Could not count notebooks under {}: {}", root, e.getMessage());
            return 0;
        }
    }

    private Map<String, Object> mcpInfo(String url) {
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("enabled", true);
        mcp.put("protocol", MCP_PROTOCOL);
        mcp.put("sse", url + "/api/mcp/sse");
        mcp.put("messages", url + "/api/mcp/messages");
        return mcp;
    }

    private List<Map<String, Object>> languageInfo() {
        List<Map<String, Object>> langs = new ArrayList<>();
        boolean node = typeScriptExecutionService.isNodeAvailable();
        langs.add(language("Java / JShell", true, System.getProperty("java.version")));
        langs.add(language("JavaScript", node, node ? "Node.js" : "Node.js not found"));
        langs.add(language("TypeScript", node,
            typeScriptExecutionService.isTscAvailable() ? "Node.js + tsc" : "Node.js type-stripping"));
        boolean dotnet = dotNetExecutionService.isDotNetAvailable();
        langs.add(language("C#", dotnet, dotnet ? ".NET SDK" : ".NET SDK not found"));
        langs.add(language("F#", dotnet, dotnet ? "dotnet fsi" : ".NET SDK not found"));
        langs.add(language("C++", cppExecutionService.isAvailable(), cppExecutionService.getCompilerDetail()));
        boolean python = pythonExecutionService.isPythonAvailable();
        langs.add(language("Python", python, python ? pythonExecutionService.pythonVersion() : "python3 not found"));
        return langs;
    }

    private Map<String, Object> language(String name, boolean available, String detail) {
        Map<String, Object> lang = new LinkedHashMap<>();
        lang.put("name", name);
        lang.put("available", available);
        lang.put("detail", detail == null ? "" : detail);
        return lang;
    }
}
