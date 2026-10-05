package com.barista.controller;

import com.barista.model.Deployment;
import com.barista.model.Notebook;
import com.barista.service.DeploymentService;
import com.barista.service.PluginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for bundling and installing plugins.
 *
 *   GET  /api/plugins/list     - list plugin definitions (user notebooks + built-in samples)
 *   GET  /api/plugins/targets  - the deploy targets, with labels and resolved roots
 *   POST /api/plugins/create   - create a new plugin notebook
 *   POST /api/plugins/deploy   - build the Claude Code plugin directory into a target
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginService pluginService;
    private final DeploymentService deployments;

    public PluginController(PluginService pluginService, DeploymentService deployments) {
        this.pluginService = pluginService;
        this.deployments = deployments;
    }

    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(pluginService.list());
    }

    @GetMapping("/targets")
    public ResponseEntity<List<Map<String, Object>>> targets() {
        List<Map<String, Object>> out = DeploymentService.TARGETS.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("key", t);
            m.put("label", deployments.labelFor(t));
            m.put("root", deployments.rootFor(t).toAbsolutePath().normalize().toString());
            m.put("pluginRoot", deployments.rootFor(t).resolve("plugins")
                    .toAbsolutePath().normalize().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping("/create")
    public ResponseEntity<Notebook> create(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(pluginService.create(body.get("name")));
    }

    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody Map<String, String> body) {
        String pluginId = body.get("pluginId");
        String target = body.getOrDefault("target", "project");
        if (pluginId == null || pluginId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "pluginId required"));
        }
        try {
            Deployment d = pluginService.deploy(pluginId, target);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("slug", d.getSlug());
            out.put("target", d.getTarget());
            out.put("targetLabel", deployments.labelFor(d.getTarget()));
            out.put("fileCount", d.getPaths().size());
            out.put("root", deployments.rootFor(d.getTarget())
                    .resolve("plugins").resolve(d.getSlug())
                    .toAbsolutePath().normalize().toString());
            out.put("deployedAt", d.getDeployedAt());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false,
                    "error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
}
