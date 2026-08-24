package com.barista.controller;

import com.barista.model.PyPiPackageInfo;
import com.barista.service.PyPiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for PyPI package management (Python cells).
 *
 * GET    /api/pypi/packages             — list installed packages
 * POST   /api/pypi/packages/install     — install a package { name, version? }
 * DELETE /api/pypi/packages/{name}      — remove a package
 * GET    /api/pypi/packages/search?q=   — look up a package on PyPI (exact name)
 * GET    /api/pypi/status               — check if Python is available
 */
@RestController
@RequestMapping("/api/pypi")
public class PyPiController {

    private final PyPiService pyPiService;

    public PyPiController(PyPiService pyPiService) {
        this.pyPiService = pyPiService;
    }

    @GetMapping("/packages")
    public ResponseEntity<List<PyPiPackageInfo>> listPackages() {
        return ResponseEntity.ok(pyPiService.getInstalledPackages());
    }

    @PostMapping("/packages/install")
    public ResponseEntity<?> installPackage(@RequestBody Map<String, String> body) {
        String name    = body.get("name");
        String version = body.getOrDefault("version", "latest");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        try {
            PyPiPackageInfo pkg = pyPiService.installPackage(name.trim(), version.trim());
            return ResponseEntity.ok(pkg);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/packages/{name}")
    public ResponseEntity<?> removePackage(@PathVariable String name) {
        try {
            boolean removed = pyPiService.removePackage(name);
            return ResponseEntity.ok(Map.of("removed", removed));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/packages/search")
    public ResponseEntity<?> searchPackages(@RequestParam String q) {
        try {
            return ResponseEntity.ok(pyPiService.searchPackages(q));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> pythonStatus() {
        return ResponseEntity.ok(pyPiService.pythonStatus());
    }
}
