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
        String jarPath;
        try {
            jarPath = findJarPath();
        } catch (Exception e) {
            log.warn("Could not locate the JAR ({}), falling back to exit 42", e.toString());
            return false;
        }
        if (jarPath == null) {
            log.info("No runnable JAR found — relying on external watchdog (exit 42)");
            return false;
        }

        String javaExe = findJavaExecutable();
        List<String> jvmArgs = getJvmArgs();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        try {
            Path script = writeTrampolineScript(isWindows);
            launchScript(isWindows, script, javaExe, jvmArgs, jarPath);
            log.info("Trampoline launched — JAR: {}", jarPath);
            return true;
        } catch (Exception e) {
            log.warn("Trampoline failed ({}), falling back to exit 42", e.getMessage());
            return false;
        }
    }

    /**
     * Write the trampoline: wait for the port to be released, then relaunch.
     *
     * <p>The script body is FIXED TEXT. The command to run is not interpolated into
     * it - it arrives as script arguments, passed through ProcessBuilder's argv list,
     * so nothing is ever concatenated into a shell string. The values involved come
     * from the JVM's own runtime rather than any request, but building a shell script
     * by concatenation is the kind of thing that is safe until one day it is not, and
     * this costs nothing to avoid.
     */
    private Path writeTrampolineScript(boolean isWindows) throws IOException {
        Path script;

        if (isWindows) {
            script = Files.createTempFile("barista-restart-", ".bat");
            Files.writeString(script,
                "@echo off\r\n" +
                "setlocal\r\n" +
                ":wait\r\n" +
                "netstat -ano 2>nul | findstr \":8585\" | findstr \"LISTENING\" >nul\r\n" +
                "if %errorlevel% == 0 (\r\n" +
                "    timeout /t 1 /nobreak >nul\r\n" +
                "    goto wait\r\n" +
                ")\r\n" +
                // %* is the argv this script was invoked with - the java command.
                "start \"Arima Notebooks\" %*\r\n" +
                "del \"%~f0\"\r\n");
        } else {
            script = Files.createTempFile("barista-restart-", ".sh");
            Files.writeString(script,
                "#!/bin/bash\n" +
                "while nc -z localhost 8585 2>/dev/null; do sleep 1; done\n" +
                // "$@" preserves each argument exactly as passed, spaces included.
                "\"$@\" &\n" +
                "rm -- \"$0\"\n");
            script.toFile().setExecutable(true);
        }
        return script;
    }

    /** Run the trampoline, handing it the relaunch command as separate arguments. */
    private void launchScript(boolean isWindows, Path script, String javaExe,
                              List<String> jvmArgs, String jarPath) throws IOException {
        List<String> cmd = new ArrayList<>();
        if (isWindows) {
            // Empty "" title arg so `start` treats the next token as the command,
            // not as a window title - survives temp paths that contain spaces.
            cmd.addAll(List.of("cmd", "/c", "start", "\"\"", "/b", script.toString()));
        } else {
            cmd.addAll(List.of("/bin/bash", script.toString()));
        }
        cmd.add(javaExe);
        cmd.addAll(jvmArgs);
        cmd.add("-jar");
        cmd.add(jarPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
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
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // Running from a Spring Boot fat jar, the code source is a nested URL such
            // as jar:file:/...!/BOOT-INF/classes!/ - not hierarchical, so new File(uri)
            // throws IllegalArgumentException. Only URISyntaxException was caught, so it
            // escaped findJarPath, killed the restart thread, and the server never
            // restarted at all: the UI's restart button silently did nothing whenever
            // Arima ran from the packaged jar, which is every normal install.
        }

        // 2. Fall back to the built jar in target/. Matched by pattern rather than an
        // exact name so a version bump cannot quietly disable restarting again.
        File[] built = new File("target").listFiles((d, n) ->
                n.startsWith("arima-notebooks-") && n.endsWith(".jar") && !n.endsWith(".original"));
        if (built != null && built.length > 0) {
            java.util.Arrays.sort(built, java.util.Comparator.comparingLong(File::lastModified).reversed());
            return built[0].getAbsolutePath();
        }

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
                result.add(arg);
            }
        }
        return result;
    }
}
