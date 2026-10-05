package com.barista.service;

import com.barista.model.Deployment;
import com.barista.util.BaristaHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Where a definition goes when you deploy it, and how to take it back.
 *
 * Arima can write an agent, skill or plugin out of its notebook store and into the files an agentic
 * CLI actually reads. Three targets:
 *
 * <ul>
 *   <li>{@code project} — the repo root's {@code .claude/}: this checkout only.</li>
 *   <li>{@code user} — {@code ~/.claude/}: every project on this machine.</li>
 *   <li>{@code bundle} — {@code data/plugins/}: a staged copy, nothing installed.</li>
 * </ul>
 *
 * Every write is recorded in {@code data/deployments.json}, which is what makes a deploy
 * reversible: {@link #undeploy} deletes exactly the paths recorded for that definition and target,
 * and nothing else. Writes are confined to the resolved target root — a definition whose slug tries
 * to escape it is rejected.
 */
@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);

    /** The three deploy targets, in the order the UI offers them. */
    public static final List<String> TARGETS = List.of("project", "user", "bundle");

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path recordFile;
    private final Path dataRoot;

    public DeploymentService(@Value("${barista.data.dir:data}") String dataDir) {
        this.dataRoot = Paths.get(dataDir);
        this.recordFile = dataRoot.resolve("deployments.json");
    }

    // -- Target roots ----------------------------------------------------------

    /** Normalize a requested target, defaulting to {@code project}. */
    public String normalizeTarget(String target) {
        String t = target == null ? "" : target.trim().toLowerCase();
        return TARGETS.contains(t) ? t : "project";
    }

    /**
     * The directory a target writes into. {@code project} and {@code user} resolve to that scope's
     * {@code .claude/}; {@code bundle} resolves to {@code data/}. A plugin's own
     * {@code plugins/<slug>} prefix then lands in the right place for each:
     * {@code .claude/plugins/<slug>} when installed, {@code data/plugins/<slug>} when staged.
     */
    public Path rootFor(String target) {
        return switch (normalizeTarget(target)) {
            case "user"   -> Paths.get(System.getProperty("user.home", ".")).resolve(".claude");
            case "bundle" -> dataRoot;
            default       -> BaristaHome.root().toPath().resolve(".claude");
        };
    }

    /**
     * The directory a <em>provider</em> creates its own native folder under — each one appends its
     * own ({@code .claude/}, {@code .github/}, {@code .antigravity/}), so this is one level above
     * {@link #rootFor}. {@code bundle} stages under {@code data/staged} rather than beside the
     * plugin bundles.
     */
    public Path scopeRoot(String target) {
        return switch (normalizeTarget(target)) {
            case "user"   -> Paths.get(System.getProperty("user.home", "."));
            case "bundle" -> dataRoot.resolve("staged");
            default       -> BaristaHome.root().toPath();
        };
    }

    /** A human label for a target, for toasts and the deployments list. */
    public String labelFor(String target) {
        return switch (normalizeTarget(target)) {
            case "user"   -> "this machine (home directory)";
            case "bundle" -> "staged under data/ (nothing installed)";
            default       -> "this project (repo root)";
        };
    }

    /**
     * Resolve {@code relative} inside the target root, creating parent directories. Rejects any
     * path that would escape the root once normalized.
     */
    public Path resolveWithin(String target, String relative) throws IOException {
        Path root = rootFor(target).toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Refusing to write outside the deploy target: " + relative);
        }
        Files.createDirectories(resolved.getParent());
        return resolved;
    }

    /** Write one file into the target and return its absolute path. */
    public Path write(String target, String relative, String content) throws IOException {
        Path file = resolveWithin(target, relative);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // -- The record ------------------------------------------------------------

    /**
     * Record a deploy. Replaces any previous record for the same definition and target, removing
     * files the earlier deploy wrote that this one no longer produces (a rename leaves no orphan).
     */
    public synchronized Deployment record(String id, String kind, String slug, String target,
                                          String provider, List<Path> paths) {
        String t = normalizeTarget(target);
        List<String> written = paths.stream()
                .map(p -> p.toAbsolutePath().normalize().toString())
                .distinct()
                .collect(Collectors.toList());

        List<Deployment> all = load();
        Optional<Deployment> previous = all.stream()
                .filter(d -> id.equals(d.getId()) && t.equals(d.getTarget()))
                .findFirst();
        previous.ifPresent(prev -> {
            prev.getPaths().stream()
                    .filter(p -> !written.contains(p))
                    .forEach(this::deleteQuietly);
            all.remove(prev);
        });

        Deployment d = new Deployment(id, kind, slug, t, provider, written, Instant.now().toString());
        all.add(d);
        save(all);
        log.info("Deployed {} '{}' to {} ({} file(s))", kind, slug, t, written.size());
        return d;
    }

    /** Everything currently deployed, newest first. */
    public synchronized List<Map<String, Object>> deployments() {
        List<Deployment> all = load();
        all.sort(Comparator.comparing(Deployment::getDeployedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Deployment d : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("kind", d.getKind());
            m.put("slug", d.getSlug());
            m.put("target", d.getTarget());
            m.put("targetLabel", labelFor(d.getTarget()));
            m.put("provider", d.getProvider());
            m.put("paths", displayPaths(d));
            m.put("deployedAt", d.getDeployedAt());
            m.put("present", d.getPaths().stream().anyMatch(p -> Files.exists(Paths.get(p))));
            out.add(m);
        }
        return out;
    }

    /** Whether a definition is deployed to a given target. */
    public synchronized boolean isDeployed(String id, String target) {
        String t = normalizeTarget(target);
        return load().stream().anyMatch(d -> id.equals(d.getId()) && t.equals(d.getTarget()));
    }

    /**
     * Remove a deploy: delete the recorded paths (and any directories they emptied, up to the
     * target root) and drop the record. Returns the number of files removed.
     */
    public synchronized int undeploy(String id, String target) {
        String t = normalizeTarget(target);
        List<Deployment> all = load();
        Optional<Deployment> hit = all.stream()
                .filter(d -> id.equals(d.getId()) && t.equals(d.getTarget()))
                .findFirst();
        if (hit.isEmpty()) return 0;

        Deployment d = hit.get();
        int removed = 0;
        for (String p : d.getPaths()) {
            if (deleteQuietly(p)) removed++;
        }
        all.remove(d);
        save(all);
        log.info("Undeployed {} '{}' from {} ({} file(s) removed)", d.getKind(), d.getSlug(), t, removed);
        return removed;
    }

    // -- internals -------------------------------------------------------------

    /** Paths relative to their target's scope where possible — nicer in the UI than absolutes. */
    private List<String> displayPaths(Deployment d) {
        Path scope = scopeRoot(d.getTarget()).toAbsolutePath().normalize();
        Path root = rootFor(d.getTarget()).toAbsolutePath().normalize();
        return d.getPaths().stream().map(p -> {
            Path abs = Paths.get(p).toAbsolutePath().normalize();
            Path base = abs.startsWith(scope) ? scope : (abs.startsWith(root) ? root : null);
            if (base == null) return p.replace('\\', '/');
            return base.relativize(abs).toString().replace('\\', '/');
        }).collect(Collectors.toList());
    }

    /** Delete a file and prune the directories it leaves empty, stopping at the target root. */
    private boolean deleteQuietly(String path) {
        try {
            Path p = Paths.get(path);
            boolean existed = Files.deleteIfExists(p);
            pruneEmptyParents(p.getParent());
            return existed;
        } catch (IOException e) {
            log.warn("Could not remove deployed file {}: {}", path, e.getMessage());
            return false;
        }
    }

    private void pruneEmptyParents(Path dir) {
        List<Path> roots = new ArrayList<>();
        for (String t : TARGETS) {
            roots.add(rootFor(t).toAbsolutePath().normalize());
            roots.add(scopeRoot(t).toAbsolutePath().normalize());
        }
        Path cursor = dir == null ? null : dir.toAbsolutePath().normalize();
        while (cursor != null && !roots.contains(cursor)) {
            // Only climb while we are still inside one of our own target roots, and never remove a
            // target's own top-level directory (`.claude`, `.github`, …) — only what we created in it.
            final Path c = cursor;
            if (roots.stream().noneMatch(c::startsWith)) return;
            if (roots.contains(cursor.getParent())) return;
            try {
                try (var entries = Files.list(cursor)) {
                    if (entries.findAny().isPresent()) return;
                }
                Files.delete(cursor);
            } catch (IOException e) {
                return;
            }
            cursor = cursor.getParent();
        }
    }

    private List<Deployment> load() {
        if (!Files.exists(recordFile)) return new ArrayList<>();
        try {
            Deployment[] arr = mapper.readValue(Files.readString(recordFile, StandardCharsets.UTF_8),
                    Deployment[].class);
            return new ArrayList<>(List.of(arr));
        } catch (Exception e) {
            log.warn("Could not read {} — starting a fresh deployment record: {}",
                    recordFile, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void save(List<Deployment> all) {
        try {
            Files.createDirectories(recordFile.getParent());
            Files.writeString(recordFile, mapper.writeValueAsString(all), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Could not persist the deployment record to {}: {}", recordFile, e.getMessage());
        }
    }
}
