package com.barista.controller;

import com.barista.service.ServerInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server lifecycle — live metadata, graceful shutdown, and self-restart.
 *
 * <h3>Restart strategy</h3>
 * <ol>
 *   <li>Write a tiny OS trampoline script to a temp file.</li>
 *   <li>Execute the script in the background.</li>
 *   <li>The script waits until port 8585 is free, then re-launches the JAR.</li>
 *   <li>Exit the current JVM with code 0.</li>
 * </ol>
 * <p>This works whether Arima was started via {@code start.sh}, {@code start.bat},
 * or {@code mvn spring-boot:run} — as long as a built JAR exists in {@code target/}.
 * If no JAR is found, the server exits with code {@value #EXIT_RESTART} so an
 * external watchdog (start scripts) can handle the restart instead.</p>
 *
 * <h3>Shutdown</h3>
 * Respects {@code server.shutdown=graceful} from {@code application.properties},
 * drains in-flight requests, then exits with code 0.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    /** Exit code that tells start scripts to relaunch when self-restart is not possible. */
    static final int EXIT_RESTART = 42;

    private final ConfigurableApplicationContext context;
    private final ServerInfoService serverInfoService;

    public SystemController(ConfigurableApplicationContext context,
                            ServerInfoService serverInfoService) {
        this.context = context;
        this.serverInfoService = serverInfoService;
    }

    // ── Endpoints ────────────────────────────────────────────────────────────

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(serverInfoService.getInfo());
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, String>> shutdown() {
        log.info("Graceful shutdown requested via API");
        scheduleExit(0, false);
        return ResponseEntity.ok(Map.of(
            "status",  "shutting_down",
            "message", "Arima is shutting down"
        ));
    }

    @PostMapping("/restart")
    public ResponseEntity<Map<String, String>> restart() {
        log.info("Graceful restart requested via API");
        scheduleExit(EXIT_RESTART, true);
        return ResponseEntity.ok(Map.of(
            "status",  "restarting",
            "message", "Arima is restarting"
        ));
    }

    /**
     * Restart after an update has rebuilt the jar. Same path as the API restart, so
     * the launcher relaunches the newly built artifact rather than the running one.
     */
    public void restartForUpdate() {
        log.info("Restarting to finish an update");
        scheduleExit(EXIT_RESTART, true);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void scheduleExit(int fallbackCode, boolean tryRestart) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            if (tryRestart) {
                boolean spawned = trySpawnTrampoline();
                int code = spawned ? 0 : fallbackCode;
                SpringApplication.exit(context, () -> code);
            } else {
                SpringApplication.exit(context, () -> 0);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Write a trampoline script that waits for port 8585 to be released,
     * then re-launches the Arima JAR.  Returns true if the script was
     * successfully written and started; false means the caller should
     * fall back to the external watchdog (exit code 42).
     */
    private boolean trySpawnTrampoline() {
        String jarPath = findJarPath();
        if (jarPath == null) {
            log.info("No runnable JAR found — relying on external watchdog (exit 42)");
            return false;
        }

        String javaExe = findJavaExecutable();
        List<String> jvmArgs = getJvmArgs();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        try {
            Path script = writeTrampolineScript(isWindows, javaExe, jvmArgs, jarPath);
            launchScript(isWindows, script);
            log.info("Trampoline launched — JAR: {}", jarPath);
            return true;
        } catch (Exception e) {
            log.warn("Trampoline failed ({}), falling back to exit 42", e.getMessage());
            return false;
        }
    }

    private Path writeTrampolineScript(boolean isWindows, String javaExe,
                                       List<String> jvmArgs, String jarPath) throws IOException {
        String jvmArgsStr = String.join(" ", jvmArgs);
        Path script;

        if (isWindows) {
            script = Files.createTempFile("barista-restart-", ".bat");
            String content =
                "@echo off\r\n" +
                "setlocal\r\n" +
                ":wait\r\n" +
                "netstat -ano 2>nul | findstr \":8585\" | findstr \"LISTENING\" >nul\r\n" +
                "if %errorlevel% == 0 (\r\n" +
                "    timeout /t 1 /nobreak >nul\r\n" +
                "    goto wait\r\n" +
                ")\r\n" +
                "start \"Arima Notebooks\" \"" + javaExe + "\" " + jvmArgsStr +
                " -jar \"" + jarPath + "\"\r\n" +
                "del \"%~f0\"\r\n";
            Files.writeString(script, content);
        } else {
            script = Files.createTempFile("barista-restart-", ".sh");
            String content =
                "#!/bin/bash\n" +
                "while nc -z localhost 8585 2>/dev/null; do sleep 1; done\n" +
                "\"" + javaExe + "\" " + jvmArgsStr + " -jar \"" + jarPath + "\" &\n" +
                "rm -- \"$0\"\n";
            Files.writeString(script, content);
            script.toFile().setExecutable(true);
        }

        return script;
    }

    private void launchScript(boolean isWindows, Path script) throws IOException {
        ProcessBuilder pb;
        if (isWindows) {
            // Empty "" title arg so `start` treats the next token as the command,
            // not as a window title — survives temp paths that contain spaces.
            pb = new ProcessBuilder("cmd", "/c", "start", "\"\"", "/b", script.toString());
        } else {
            pb = new ProcessBuilder("/bin/bash", script.toString());
        }
        pb.inheritIO();
        pb.start();
    }

    /** Locate the Arima JAR in {@code target/}. */
    private String findJarPath() {
        // 1. Try the class source (works when running from a packaged JAR)
        try {
            URI uri = com.barista.BaristaApplication.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI();
            File f = new File(uri);
            if (f.getName().endsWith(".jar") && f.exists()) {
                return f.getAbsolutePath();
            }
        } catch (URISyntaxException ignored) {}

        // 2. Fall back to well-known JAR path relative to working directory
        File jar = new File("target/arima-notebooks-4.0.1.jar");
        if (jar.exists()) return jar.getAbsolutePath();

        return null;
    }

    /** Best-effort: find the java executable used to run the current JVM. */
    private String findJavaExecutable() {
        return ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
    }

    /** Collect relevant JVM startup flags (--add-opens, --add-exports, -D…). */
    private List<String> getJvmArgs() {
        List<String> result = new ArrayList<>();
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("--add-opens") || arg.startsWith("--add-exports")
                    || arg.startsWith("-D") || arg.startsWith("-X")) {
                result.add("\"" + arg + "\"");
            }
        }
        return result;
    }
}
