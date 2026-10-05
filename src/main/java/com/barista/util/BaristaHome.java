package com.barista.util;

import java.io.File;

/**
 * Resolves the Arima project root for spawning AI CLI subprocesses.
 *
 * <p>The {@code barista} launchers export {@code BARISTA_HOME} so the in-UI AI panel
 * runs Claude / Copilot / Gemini from the repo root, where they pick up
 * {@code AGENTS.md}, {@code .claude/} skills + agents, and the provider
 * instruction files. When the variable is absent (e.g. dev runs via
 * {@code mvn spring-boot:run}) the JVM working directory is already the repo
 * root, so the CLI inherits the right context anyway.
 */
public final class BaristaHome {

    private BaristaHome() {}

    /**
     * The directory AI CLI subprocesses should run in, or {@code null} to inherit
     * the JVM's working directory. Passing {@code null} to
     * {@link ProcessBuilder#directory(File)} is a no-op, so callers can apply the
     * result unconditionally.
     */
    public static File directory() {
        String home = System.getenv("BARISTA_HOME");
        if (home == null || home.isBlank()) {
            return null;
        }
        File dir = new File(home);
        return dir.isDirectory() ? dir : null;
    }

    /**
     * The project root as a concrete directory, never {@code null} — for callers that must
     * <em>write</em> somewhere (deploying an agent into {@code .claude/}) rather than merely hand
     * a working directory to {@link ProcessBuilder}. Falls back to the JVM's working directory,
     * which is already the repo root under {@code mvn spring-boot:run} and the launchers.
     */
    public static File root() {
        File dir = directory();
        return dir != null ? dir : new File(System.getProperty("user.dir", "."));
    }
}
