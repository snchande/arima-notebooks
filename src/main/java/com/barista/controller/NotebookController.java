package com.barista.controller;

import com.barista.model.Notebook;
import com.barista.model.UserProfile;
import com.barista.service.NotebookService;
import com.barista.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for notebook CRUD operations.
 *
 * Personal notebooks (user-scoped):
 *   GET    /api/notebooks                  - List user's personal notebooks
 *   POST   /api/notebooks                  - Create a new notebook
 *   GET    /api/notebooks/{id}             - Get a personal notebook
 *   PUT    /api/notebooks/{id}             - Save/update a notebook
 *   DELETE /api/notebooks/{id}             - Delete a notebook
 *   PATCH  /api/notebooks/{id}/metadata    - Update metadata (tags, folder) without full save
 *
 * Tutorial notebooks (shared, read-only):
 *   GET    /api/notebooks/tutorials        - List all tutorial notebooks
 *   GET    /api/notebooks/tutorials/{id}   - Get a tutorial notebook
 */
@RestController
@RequestMapping("/api/notebooks")
public class NotebookController {

    private final NotebookService notebookService;
    private final UserService userService;

    public NotebookController(NotebookService notebookService, UserService userService) {
        this.notebookService = notebookService;
        this.userService = userService;
    }

    // ── Tutorial endpoints (read-only, not user-scoped) ──────────────

    @GetMapping("/tutorials")
    public ResponseEntity<List<Map<String, Object>>> listTutorials() {
        return ResponseEntity.ok(notebookService.listTutorials());
    }

    @GetMapping("/tutorials/{id}")
    public ResponseEntity<Notebook> getTutorial(@PathVariable String id) {
        return notebookService.getTutorial(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Impact analysis & file location ───────────────────────────

    /**
     * Every cell that references {@code {id}/{anchor}} — locally or from another
     * notebook. Powers the downstream-impact chain in the UI.
     */
    @GetMapping("/{id}/references")
    public ResponseEntity<List<Map<String, Object>>> references(@PathVariable String id,
                                                                @RequestParam String anchor) {
        return ResponseEntity.ok(notebookService.findReferences(id, anchor, currentUserId()));
    }

    /** Absolute on-disk path of a notebook, so the UI can show where it lives. */
    @GetMapping("/{id}/path")
    public ResponseEntity<Map<String, String>> notebookPath(@PathVariable String id) {
        return notebookService.notebookFile(id, currentUserId())
                .map(p -> ResponseEntity.ok(Map.of("path", p.toString())))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Reveal the notebook file in the OS file manager. Local-first convenience — this
     * only ever opens a file browser at a path the server already owns, and the
     * command is built with ProcessBuilder(List) so nothing is shell-interpreted.
     */
    @PostMapping("/{id}/reveal")
    public ResponseEntity<Map<String, Object>> reveal(@PathVariable String id) {
        var file = notebookService.notebookFile(id, currentUserId());
        if (file.isEmpty()) return ResponseEntity.notFound().build();
        String path = file.get().toString();
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> cmd;
        if (os.contains("win")) {
            cmd = List.of("explorer.exe", "/select," + path);
        } else if (os.contains("mac")) {
            cmd = List.of("open", "-R", path);
        } else {
            // Linux file managers vary; open the containing directory.
            cmd = List.of("xdg-open", file.get().getParent().toString());
        }
        try {
            new ProcessBuilder(cmd).start();
            return ResponseEntity.ok(Map.of("ok", true, "path", path));
        } catch (Exception e) {
            // Explorer returns a non-zero exit even on success, so only a genuine
            // launch failure lands here — report the path so the user can copy it.
            return ResponseEntity.ok(Map.of("ok", false, "path", path,
                    "error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Open a notebook file from disk - a double-clicked .anb, or a path handed to
     * "arima open". Returns the id the UI should navigate to.
     */
    @PostMapping("/open-file")
    public ResponseEntity<?> openFile(@RequestBody Map<String, String> body) {
        String path = body == null ? null : body.get("path");
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'path'"));
        }
        try {
            String id = notebookService.openExternalFile(
                    java.nio.file.Paths.get(path), currentUserId());
            return ResponseEntity.ok(Map.of("id", id, "url", "/notebooks/" + id));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Could not open notebook: " + e.getMessage()));
        }
    }

    // ── Personal notebook endpoints ───────────────────────────────

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listNotebooks() {
        String userId = currentUserId();
        return ResponseEntity.ok(notebookService.listNotebooks(userId));
    }

    @PostMapping
    public ResponseEntity<Notebook> createNotebook(@RequestBody(required = false) Map<String, String> body) {
        String name = body != null ? body.get("name") : null;
        String mode = body != null ? body.get("mode") : null;   // notebook's default language
        Notebook nb = notebookService.createNotebook(name, currentUserId(), mode);
        return ResponseEntity.status(HttpStatus.CREATED).body(nb);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Notebook> getNotebook(@PathVariable String id) {
        return notebookService.getNotebook(id, currentUserId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Notebook> saveNotebook(@PathVariable String id,
                                                  @RequestBody Notebook notebook) {
        notebook.setId(id);
        Notebook saved = notebookService.saveNotebook(notebook, currentUserId());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Boolean>> deleteNotebook(@PathVariable String id) {
        boolean deleted = notebookService.deleteNotebook(id, currentUserId());
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * Patch just the metadata fields (tags, folder, description, etc.) without
     * sending the full notebook payload.  Reads the current notebook, merges the
     * patch fields into its metadata, and re-saves.
     */
    @PatchMapping("/{id}/metadata")
    public ResponseEntity<Map<String, Object>> patchMetadata(@PathVariable String id,
                                                              @RequestBody Map<String, Object> patch) {
        String uid = currentUserId();
        return notebookService.getNotebook(id, uid)
                .map(nb -> {
                    if (nb.getMetadata() == null) nb.setMetadata(new HashMap<>());
                    nb.getMetadata().putAll(patch);
                    notebookService.saveNotebook(nb, uid);
                    return ResponseEntity.ok(nb.getMetadata());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private String currentUserId() {
        UserProfile user = userService.getCurrentUser();
        if (user == null) throw new IllegalStateException("No authenticated user");
        return user.getId();
    }
}
