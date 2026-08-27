package com.barista.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tells you when the checked-out copy is behind {@code master}, and applies the
 * update on request.
 *
 * <p>Deliberately git-based rather than a release feed: Arima is installed by cloning
 * the repository and building it, so "is there a newer version" is exactly "does
 * origin/master have commits I do not". That also means no new outbound host - the
 * remote is whatever the user already cloned from.
 *
 * <p>Nothing is fetched or changed without being asked. The check is a read-only
 * {@code git fetch}; the update is pull, rebuild, and restart, and it is only ever
 * started by an explicit call.
 */
@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    @Value("${barista.version:unknown}")
    private String version;

    /** Cached so opening the UI does not fetch every time. */
    private final AtomicReference<Map<String, Object>> lastCheck = new AtomicReference<>();
    private volatile long lastCheckedAt = 0;

    private static final long CACHE_MS = TimeUnit.MINUTES.toMillis(15);

    /** Set while an update is running, so the UI can show progress and block a second one. */
    private final AtomicReference<String> updateState = new AtomicReference<>("idle");
    private final List<String> updateLog = new ArrayList<>();

    public Map<String, Object> check(boolean force) {
        Map<String, Object> cached = lastCheck.get();
        if (!force && cached != null && System.currentTimeMillis() - lastCheckedAt < CACHE_MS) {
            return cached;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentVersion", version);
        result.put("checkedAt", Instant.now().toString());

        if (!isGitCheckout()) {
            result.put("available", false);
            result.put("reason", "not a git checkout - update through your package manager or reinstall");
            cache(result);
            return result;
        }

        // Read-only: fetch updates the remote-tracking ref, it does not touch the
        // working tree or the current branch.
        Proc fetch = run(30, "git", "fetch", "--quiet", "origin", "master");
        if (fetch.exit != 0) {
            result.put("available", false);
            result.put("reason", "could not reach the remote: " + fetch.tail());
            cache(result);
            return result;
        }

        Proc behind = run(10, "git", "rev-list", "--count", "HEAD..origin/master");
        int commits = 0;
        try {
            commits = Integer.parseInt(behind.out.trim());
        } catch (NumberFormatException ignored) {
            // Treated as up to date rather than guessing.
        }

        result.put("available", commits > 0);
        result.put("commitsBehind", commits);

        if (commits > 0) {
            Proc subjects = run(10, "git", "log", "--oneline", "--no-decorate",
                                "-8", "HEAD..origin/master");
            List<String> changes = new ArrayList<>();
            for (String line : subjects.out.split("\n")) {
                if (!line.isBlank()) changes.add(line.trim());
            }
            result.put("changes", changes);

            Proc dirty = run(10, "git", "status", "--porcelain");
            boolean hasLocalEdits = !dirty.out.isBlank();
            result.put("localChanges", hasLocalEdits);
            if (hasLocalEdits) {
                // Never discard the user's work to install an update.
                result.put("blocked", "you have uncommitted local changes - commit or stash them first");
            }
        }

        cache(result);
        return result;
    }

    private void cache(Map<String, Object> r) {
        lastCheck.set(r);
        lastCheckedAt = System.currentTimeMillis();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", updateState.get());
        synchronized (updateLog) { m.put("log", new ArrayList<>(updateLog)); }
        return m;
    }

    /**
     * Pull, rebuild, and restart.
     *
     * <p>The restart is the launcher's job: the foreground script honours exit code 42
     * as "start me again", which is how the UI's existing restart works. Doing it any
     * other way would mean a running JVM trying to replace the jar it is executing.
     *
     * @return an error message, or null if the update started
     */
    public String applyUpdate(Runnable restartHook) {
        if (!updateState.compareAndSet("idle", "running")) {
            return "An update is already running";
        }
        synchronized (updateLog) { updateLog.clear(); }

        Map<String, Object> check = check(true);
        if (Boolean.TRUE.equals(check.get("localChanges"))) {
            updateState.set("idle");
            return "You have uncommitted local changes - commit or stash them first";
        }
        if (!Boolean.TRUE.equals(check.get("available"))) {
            updateState.set("idle");
            return "Already up to date";
        }

        Thread worker = new Thread(() -> {
            try {
                step("Pulling the latest code");
                Proc pull = run(120, "git", "pull", "--ff-only", "origin", "master");
                if (pull.exit != 0) {
                    fail("git pull failed: " + pull.tail());
                    return;
                }

                step("Building - this takes a minute");
                Proc build = run(600, mavenCommand(), "clean", "package", "-DskipTests", "-q");
                if (build.exit != 0) {
                    fail("the build failed, so the update was not applied: " + build.tail());
                    return;
                }

                step("Restarting Arima");
                updateState.set("restarting");
                if (restartHook != null) restartHook.run();
            } catch (Exception e) {
                fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "arima-update");
        worker.setDaemon(true);
        worker.start();
        return null;
    }

    private void step(String message) {
        log.info("[Update] {}", message);
        synchronized (updateLog) { updateLog.add(message); }
    }

    private void fail(String message) {
        log.error("[Update] {}", message);
        synchronized (updateLog) { updateLog.add("FAILED: " + message); }
        updateState.set("failed");
    }

    /** The wrapper is preferred, so a machine without Maven can still update. */
    private String mavenCommand() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File wrapper = new File(windows ? "mvnw.cmd" : "mvnw");
        return wrapper.isFile() ? wrapper.getAbsolutePath() : "mvn";
    }

    private boolean isGitCheckout() {
        return new File(".git").exists();
    }

    private record Proc(int exit, String out) {
        String tail() {
            String[] lines = out.strip().split("\n");
            return lines.length == 0 ? "" : lines[lines.length - 1].trim();
        }
    }

    private Proc run(int timeoutSec, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(command));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new Proc(-1, out + "\ntimed out after " + timeoutSec + "s");
            }
            return new Proc(p.exitValue(), out);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Proc(-1, String.valueOf(e.getMessage()));
        }
    }
}
