package com.barista.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.barista.model.Cell;
import com.barista.model.CellType;
import com.barista.model.Notebook;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class NotebookService {

    private static final Logger log = LoggerFactory.getLogger(NotebookService.class);
    private static final String EXTENSION = ".anb";
    /** Pre-1.1 extension. Files found with it are migrated to {@link #EXTENSION} on startup. */
    private static final String LEGACY_EXTENSION = ".vnb";

    /**
     * userId -> (notebook id -> file). Notebooks are stored under a readable filename
     * derived from their name, so the id is no longer recoverable from the path and
     * has to be indexed. Rebuilt on a miss, so an edit made outside Arima is picked up.
     */
    private final Map<String, Map<String, Path>> idIndex = new ConcurrentHashMap<>();

    @Value("${barista.notebooks.dir:notebooks}")
    private String notebooksDir;

    @Autowired
    private UserService userService;

    private final ObjectMapper objectMapper;

    public NotebookService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() throws IOException {
        // Ensure root notebooks dir exists
        Files.createDirectories(Paths.get(notebooksDir));
        // Ensure shared tutorials dir exists
        Files.createDirectories(tutorialsDir());

        // Create local user folder
        String localUserId = userService.getLocalUserId();
        Path userDir = userDir(localUserId);
        Files.createDirectories(userDir);

        // Migrate any legacy files from the flat root to the local user folder
        migrateRootNotebooks(userDir);

        // .vnb -> .anb, and UUID filenames -> readable ones
        migrateToReadableFilenames(userDir);
        migrateSharedDirExtension(tutorialsDir());
        migrateSharedDirExtension(examplesDir());

        // Seed with welcome notebook if user folder is empty
        if (listNotebooks(localUserId).isEmpty()) {
            createWelcomeNotebook(localUserId);
        }
    }

    // ── Public CRUD (all user-scoped) ────────────────────────────

    public List<Map<String, Object>> listNotebooks(String userId) {
        Path dir = userDir(userId);
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> isNotebookFile(p))
                    .map(this::readNotebookMeta)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(
                            m -> m.get("modified").toString(), Comparator.reverseOrder()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list notebooks for {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    public Optional<Notebook> getNotebook(String id, String userId) {
        Path path = notebookPath(id, userId);
        if (!Files.exists(path)) return Optional.empty();
        try {
            Notebook nb = objectMapper.readValue(path.toFile(), Notebook.class);
            nb.setFilename(path.getFileName().toString());
            return Optional.of(nb);
        } catch (IOException e) {
            log.error("Failed to read notebook {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    public Notebook createNotebook(String name, String userId) {
        return createNotebook(name, userId, null);
    }

    /**
     * Create an empty notebook. No starter cell is added — the user decides whether the first cell
     * is code or markdown. {@code defaultMode} records the notebook's default language in
     * {@code metadata.defaultMode}; new code cells adopt it. Null falls back to {@code jshell}.
     */
    public Notebook createNotebook(String name, String userId, String defaultMode) {
        Notebook nb = new Notebook();
        nb.setId(UUID.randomUUID().toString());
        nb.setName(name == null || name.isBlank() ? "Untitled Notebook" : name);
        nb.setCells(new ArrayList<>());
        nb.setCreated(LocalDateTime.now());
        nb.setModified(LocalDateTime.now());
        nb.setMetadata(new HashMap<>());
        nb.getMetadata().put("defaultMode", normalizeMode(defaultMode));

        saveNotebook(nb, userId);
        return nb;
    }

    /** Accepted cell modes; anything unrecognised falls back to jshell. */
    private static final List<String> VALID_MODES = List.of(
            "jshell", "java", "nodejs", "typescript", "csharp", "fsharp", "cpp", "python");

    private String normalizeMode(String mode) {
        if (mode == null) return "jshell";
        String m = mode.trim().toLowerCase();
        return VALID_MODES.contains(m) ? m : "jshell";
    }

    public Notebook saveNotebook(Notebook notebook, String userId) {
        notebook.setModified(LocalDateTime.now());
        if (notebook.getId() == null) notebook.setId(UUID.randomUUID().toString());
        if (notebook.getCreated() == null) notebook.setCreated(LocalDateTime.now());

        Path existing = lookup(notebook.getId(), userId);
        Path path = preferredPath(notebook, userId);
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), notebook);

            // Renaming the notebook renames its file, so the folder keeps tracking the title.
            if (existing != null && !existing.equals(path)) {
                Files.deleteIfExists(existing);
                log.info("Renamed notebook file {} -> {}",
                        existing.getFileName(), path.getFileName());
            }
            idIndex.computeIfAbsent(userId, this::buildIndex).put(notebook.getId(), path);
            log.debug("Saved notebook: {} ({}) for user {}", notebook.getName(), notebook.getId(), userId);
        } catch (IOException e) {
            log.error("Failed to save notebook {}: {}", notebook.getId(), e.getMessage());
            throw new RuntimeException("Failed to save notebook", e);
        }
        return notebook;
    }

    public boolean deleteNotebook(String id, String userId) {
        try {
            Path path = notebookPath(id, userId);
            idIndex.getOrDefault(userId, Map.of()).remove(id);
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("Failed to delete notebook {}: {}", id, e.getMessage());
            return false;
        }
    }

    // ── Tutorial / Example support (shared, not user-scoped) ────────

    private Path tutorialsDir() {
        return Paths.get(notebooksDir, "tutorials");
    }

    private Path examplesDir() {
        return Paths.get(notebooksDir, "examples");
    }

    /** Lists all tutorial AND example notebooks merged into one list. */
    public List<Map<String, Object>> listTutorials() {
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        all.addAll(scanDir(tutorialsDir(), "tutorial"));
        all.addAll(scanDir(examplesDir(), "example"));
        all.sort(Comparator.comparing(m -> m.getOrDefault("id", "").toString()));
        return all;
    }

    private List<Map<String, Object>> scanDir(Path dir, String defaultCategory) {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> isNotebookFile(p))
                    .map(p -> readNotebookMeta(p, defaultCategory))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to scan directory {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /** Loads a tutorial or example notebook by ID, checking both directories. */
    public Optional<Notebook> getTutorial(String id) {
        // Check tutorials first, then examples
        for (Path dir : new Path[]{tutorialsDir(), examplesDir()}) {
            Path path = dir.resolve(id + EXTENSION);
            if (Files.exists(path)) {
                try {
                    Notebook nb = objectMapper.readValue(path.toFile(), Notebook.class);
                    nb.setFilename(path.getFileName().toString());
                    return Optional.of(nb);
                } catch (IOException e) {
                    log.error("Failed to read notebook {}: {}", id, e.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    // ── Impact analysis ──────────────────────────────────────────

    private static final Pattern ANNOTATION =
            Pattern.compile("(?m)^\\s*(?://@|#@)\\s*(anchor|depends|steps)\\s*:\\s*(.+)$");

    /**
     * Find every cell — in this notebook and every other notebook the user owns, plus
     * tutorials — that references {@code notebookId/anchor}. This is what powers the
     * "downstream impact" chain in the UI: before you change a cell, see who is
     * standing on it.
     *
     * A reference is either a local {@code //@ depends: anchor} inside the same
     * notebook, or a cross-notebook {@code //@ depends: notebook:<id>/<anchor>}.
     */
    public List<Map<String, Object>> findReferences(String notebookId, String anchor, String userId) {
        List<Map<String, Object>> hits = new ArrayList<>();
        String crossRef = "notebook:" + notebookId + "/" + anchor;

        List<Notebook> all = new ArrayList<>();
        for (Map<String, Object> meta : listNotebooks(userId)) {
            Object id = meta.get("id");
            if (id != null) getNotebook(id.toString(), userId).ifPresent(all::add);
        }
        for (Map<String, Object> meta : listTutorials()) {
            Object id = meta.get("id");
            if (id != null) getTutorial(id.toString()).ifPresent(all::add);
        }

        for (Notebook nb : all) {
            boolean sameNotebook = notebookId.equals(nb.getId());
            for (Cell c : nb.getCells() == null ? List.<Cell>of() : nb.getCells()) {
                String src = c.getSource() == null ? "" : c.getSource();
                boolean local = false, cross = src.contains(crossRef);
                if (sameNotebook) {
                    Matcher m = ANNOTATION.matcher(src);
                    while (m.find()) {
                        if (!"anchor".equals(m.group(1))) {
                            for (String part : m.group(2).split(",")) {
                                if (part.trim().equals(anchor)) { local = true; break; }
                            }
                        }
                    }
                }
                if (!local && !cross) continue;
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("notebookId", nb.getId());
                hit.put("notebookName", nb.getName());
                hit.put("cellId", c.getId());
                hit.put("anchor", anchorOf(src));
                hit.put("kind", cross ? "cross-notebook" : "local");
                hit.put("preview", firstMeaningfulLine(src));
                hits.add(hit);
            }
        }
        return hits;
    }

    /** The anchor a cell declares, or null. */
    private String anchorOf(String src) {
        Matcher m = ANNOTATION.matcher(src == null ? "" : src);
        while (m.find()) {
            if ("anchor".equals(m.group(1))) return m.group(2).trim();
        }
        return null;
    }

    /** First non-annotation, non-comment line — used as a one-line cell preview. */
    private String firstMeaningfulLine(String src) {
        for (String l : (src == null ? "" : src).split("\n")) {
            String t = l.strip();
            if (t.isEmpty() || t.startsWith("//@") || t.startsWith("#@")
                    || t.startsWith("//") || t.startsWith("#")) continue;
            return t.length() > 80 ? t.substring(0, 80) + "…" : t;
        }
        return "";
    }

    /**
     * Resolve a notebook file the user opened from outside Arima - a double-clicked
     * {@code .anb}, or a path passed to {@code arima open}.
     *
     * A file already inside the user's folder is simply opened. One from anywhere else
     * is copied in, so opening a notebook someone sent you does not leave Arima
     * pointing at a file it does not own. A copy whose id already exists is given a
     * fresh one rather than overwriting the notebook already there.
     *
     * @return the id to open in the UI
     */
    public String openExternalFile(Path file, String userId) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a file: " + file);
        }
        Notebook nb = objectMapper.readValue(file.toFile(), Notebook.class);
        if (nb.getId() == null || nb.getId().isBlank()) {
            nb.setId(UUID.randomUUID().toString());
        }

        Path canonical = file.toAbsolutePath().normalize();
        Path owned = userDir(userId).toAbsolutePath().normalize();
        if (canonical.startsWith(owned)) {
            idIndex.computeIfAbsent(userId, this::buildIndex).put(nb.getId(), canonical);
            return nb.getId();
        }

        if (lookup(nb.getId(), userId) != null) {
            nb.setId(UUID.randomUUID().toString());
            nb.setName(nb.getName() + " (imported)");
        }
        saveNotebook(nb, userId);
        log.info("Imported external notebook {} as {}", file.getFileName(), nb.getId());
        return nb.getId();
    }

    // ── Path helpers ─────────────────────────────────────────────

    /** On-disk location of a notebook, for "open file location" in the UI. */
    public Optional<Path> notebookFile(String id, String userId) {
        Path p = notebookPath(id, userId);
        if (Files.exists(p)) return Optional.of(p.toAbsolutePath());
        Path t = Paths.get(notebooksDir, "tutorials", id + EXTENSION);
        return Files.exists(t) ? Optional.of(t.toAbsolutePath()) : Optional.empty();
    }

    /**
     * Where a notebook with this id currently lives, or where a new one would go.
     * Resolution goes through the id index because the filename is derived from the
     * notebook's name, not its id.
     */
    private Path notebookPath(String id, String userId) {
        Path known = lookup(id, userId);
        return known != null ? known : userDir(userId).resolve(id + EXTENSION);
    }

    /** True for any file Arima stores a notebook in, current or legacy extension. */
    private boolean isNotebookFile(Path p) {
        String n = p.toString();
        return (n.endsWith(EXTENSION) || n.endsWith(LEGACY_EXTENSION)) && Files.isRegularFile(p);
    }

    /** Find the file holding this id, rebuilding the index once if the cache misses. */
    private Path lookup(String id, String userId) {
        Map<String, Path> index = idIndex.computeIfAbsent(userId, this::buildIndex);
        Path hit = index.get(id);
        if (hit != null && Files.exists(hit)) return hit;
        index = buildIndex(userId);
        idIndex.put(userId, index);
        return index.get(id);
    }

    private Map<String, Path> buildIndex(String userId) {
        Map<String, Path> index = new HashMap<>();
        Path dir = userDir(userId);
        if (!Files.exists(dir)) return index;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(this::isNotebookFile).forEach(p -> {
                try {
                    JsonNode node = objectMapper.readTree(p.toFile());
                    JsonNode idNode = node.get("id");
                    if (idNode != null && !idNode.asText().isBlank()) {
                        index.put(idNode.asText(), p);
                    }
                } catch (IOException e) {
                    log.warn("Skipping unreadable notebook {}: {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Failed to index notebooks for {}: {}", userId, e.getMessage());
        }
        return index;
    }

    /**
     * A readable, filesystem-safe filename for a notebook: the name, lowercased and
     * hyphenated. A short id suffix is appended only when another notebook already
     * holds that filename, so the common case stays clean.
     */
    private Path preferredPath(Notebook nb, String userId) {
        String slug = slugify(nb.getName());
        if (slug.isBlank()) slug = "untitled";

        Path dir = userDir(userId);
        Path candidate = dir.resolve(slug + EXTENSION);

        if (Files.exists(candidate) && !ownsFile(candidate, nb.getId())) {
            String suffix = nb.getId().replace("-", "");
            suffix = suffix.substring(0, Math.min(6, suffix.length()));
            candidate = dir.resolve(slug + "-" + suffix + EXTENSION);
        }
        return candidate;
    }

    private boolean ownsFile(Path path, String id) {
        try {
            JsonNode node = objectMapper.readTree(path.toFile());
            JsonNode idNode = node.get("id");
            return idNode != null && idNode.asText().equals(id);
        } catch (IOException e) {
            return false;
        }
    }

    static String slugify(String name) {
        if (name == null) return "";
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase();

        // Language names carry meaning in punctuation that would otherwise be stripped,
        // collapsing "C++" and "C#" onto the same slug.
        s = s.replace("c++", "cpp")
             .replace("f#", "fsharp")
             .replace("c#", "csharp")
             .replace("++", "pp")
             .replace("#", "sharp");

        s = s.replaceAll("[^a-z0-9]+", "-")
             .replaceAll("^-+|-+$", "");
        return s.length() > 60 ? s.substring(0, 60).replaceAll("-+$", "") : s;
    }

    private Path userDir(String userId) {
        return Paths.get(notebooksDir, userId);
    }

    // ── Metadata ─────────────────────────────────────────────────

    private Map<String, Object> readNotebookMeta(Path path) {
        return readNotebookMeta(path, null);
    }

    private Map<String, Object> readNotebookMeta(Path path, String defaultCategory) {
        try {
            Notebook nb = objectMapper.readValue(path.toFile(), Notebook.class);
            Map<String, Object> meta = new HashMap<>();
            meta.put("id", nb.getId());
            meta.put("name", nb.getName());
            meta.put("description", nb.getDescription());
            meta.put("created", nb.getCreated());
            meta.put("modified", nb.getModified());
            meta.put("cellCount", nb.getCells() == null ? 0 : nb.getCells().size());
            Map<String, Object> nbMeta = nb.getMetadata() != null
                    ? new HashMap<>(nb.getMetadata())
                    : new HashMap<>();
            // Stamp category if not already set in the file metadata
            if (defaultCategory != null && !nbMeta.containsKey("category")) {
                nbMeta.put("category", defaultCategory);
            }
            meta.put("metadata", nbMeta);
            return meta;
        } catch (IOException e) {
            log.warn("Failed to read notebook metadata from {}: {}", path, e.getMessage());
            return null;
        }
    }

    // ── Migration ────────────────────────────────────────────────

    private void migrateRootNotebooks(Path userDir) {
        Path rootDir = Paths.get(notebooksDir);
        try (Stream<Path> entries = Files.list(rootDir)) {
            entries
                .filter(p -> isNotebookFile(p))
                .forEach(p -> {
                    Path dest = userDir.resolve(p.getFileName());
                    if (!Files.exists(dest)) {
                        try {
                            Files.move(p, dest, StandardCopyOption.ATOMIC_MOVE);
                            log.info("Migrated notebook {} → {}", p.getFileName(), userDir.getFileName());
                        } catch (IOException e) {
                            log.warn("Could not migrate {}: {}", p.getFileName(), e.getMessage());
                        }
                    }
                });
        } catch (IOException e) {
            log.warn("Migration scan failed: {}", e.getMessage());
        }
    }

    /**
     * Bring a user folder up to the current storage convention: the {@code .anb}
     * extension, and a filename derived from the notebook's name rather than its id.
     * Runs once per file - anything already conforming is left alone.
     */
    private void migrateToReadableFilenames(Path userDir) {
        if (!Files.exists(userDir)) return;
        List<Path> stale;
        try (Stream<Path> files = Files.list(userDir)) {
            stale = files.filter(this::isNotebookFile).collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Filename migration scan failed: {}", e.getMessage());
            return;
        }

        int renamed = 0;
        for (Path p : stale) {
            try {
                Notebook nb = objectMapper.readValue(p.toFile(), Notebook.class);
                if (nb.getId() == null || nb.getId().isBlank()) continue;

                String slug = slugify(nb.getName());
                if (slug.isBlank()) slug = "untitled";
                Path target = userDir.resolve(slug + EXTENSION);

                if (target.equals(p)) continue;
                if (Files.exists(target)) {
                    String suffix = nb.getId().replace("-", "");
                    suffix = suffix.substring(0, Math.min(6, suffix.length()));
                    target = userDir.resolve(slug + "-" + suffix + EXTENSION);
                    if (Files.exists(target)) continue;
                }

                Files.move(p, target, StandardCopyOption.ATOMIC_MOVE);
                log.info("Notebook file {} -> {}", p.getFileName(), target.getFileName());
                renamed++;
            } catch (IOException e) {
                log.warn("Could not rename {}: {}", p.getFileName(), e.getMessage());
            }
        }
        if (renamed > 0) {
            log.info("Renamed {} notebook file(s) to the readable .anb convention", renamed);
            idIndex.clear();
        }
    }

    /**
     * Tutorials and examples are already named by their readable id, so they only need
     * the extension moved from {@code .vnb} to {@code .anb}.
     */
    private void migrateSharedDirExtension(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(LEGACY_EXTENSION) && Files.isRegularFile(p))
                 .forEach(p -> {
                     String base = p.getFileName().toString();
                     Path target = p.resolveSibling(
                             base.substring(0, base.length() - LEGACY_EXTENSION.length()) + EXTENSION);
                     if (Files.exists(target)) return;
                     try {
                         Files.move(p, target, StandardCopyOption.ATOMIC_MOVE);
                         log.info("Notebook file {} -> {}", base, target.getFileName());
                     } catch (IOException e) {
                         log.warn("Could not rename {}: {}", base, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("Extension migration scan failed for {}: {}", dir, e.getMessage());
        }
    }

    // ── Welcome notebook ─────────────────────────────────────────

    private void createWelcomeNotebook(String userId) {
        Notebook nb = new Notebook();
        nb.setId("welcome");
        nb.setName("Welcome to Arima Notebooks");
        nb.setDescription("A quick introduction to Arima Notebooks");
        nb.setCells(new ArrayList<>());
        nb.setCreated(LocalDateTime.now());
        nb.setModified(LocalDateTime.now());
        nb.setMetadata(new HashMap<>());

        nb.getCells().add(makeCell("cell-1", CellType.MARKDOWN,
                "# Welcome to Arima Notebooks\n\nArima Notebooks is an interactive Java environment powered by JShell.\n\n- Write and execute Java code cell by cell\n- Install Maven packages from the **Packages** tab\n- Get AI assistance from the **AI** tab\n- Configure settings in the **Settings** tab"));
        nb.getCells().add(makeCell("cell-2", CellType.CODE,
                "// Hello World - click Run or press Shift+Enter\nSystem.out.println(\"Hello from Arima Notebooks!\");"));
        nb.getCells().add(makeCell("cell-3", CellType.CODE,
                "// Variables persist between cells\nvar greeting = \"Arima\";\nvar version = 1.0;\nString.format(\"Welcome to %s v%.1f\", greeting, version)"));
        nb.getCells().add(makeCell("cell-4", CellType.CODE,
                "// Java streams work great in Arima!\nimport java.util.stream.*;\n\nIntStream.range(1, 6)\n         .mapToObj(i -> \"Item \" + i)\n         .forEach(System.out::println);"));

        saveNotebook(nb, userId);
        log.info("Created welcome notebook for user {}", userId);
    }

    private Cell makeCell(String id, CellType type, String source) {
        Cell c = new Cell();
        c.setId(id);
        c.setType(type);
        c.setSource(source);
        return c;
    }
}
