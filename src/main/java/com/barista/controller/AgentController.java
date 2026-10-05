package com.barista.controller;

import com.barista.model.Notebook;
import com.barista.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for authoring, running, and exporting agents & skills.
 *
 *   GET  /api/agents/list        - list agent/skill definitions (user notebooks + built-in samples)
 *   POST /api/agents/create      - create a new agent/skill notebook (pre-seeded)
 *   POST /api/agents/run         - run the agent/skill against a task (streams via STOMP)
 *   POST /api/agents/deploy      - write the provider's native files into a target
 *   POST /api/agents/export      - alias of deploy with target "project" (kept for callers)
 *   GET  /api/agents/deployments - what Arima has deployed, across targets
 *   POST /api/agents/undeploy    - remove a deploy, file by recorded file
 *   GET  /api/agents/providers   - provider availability
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Boolean>> providers() {
        return ResponseEntity.ok(agentService.providerStatus());
    }

    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(agentService.list());
    }

    @PostMapping("/create")
    public ResponseEntity<Notebook> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String kind = body.getOrDefault("kind", "agent");
        return ResponseEntity.ok(agentService.create(name, kind));
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody Map<String, String> body) {
        String notebookId = body.get("notebookId");
        String task       = body.getOrDefault("task", "");
        String provider   = body.getOrDefault("provider", "claude");
        String sessionId  = body.get("sessionId");
        if (notebookId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "notebookId required"));
        }
        try {
            String output = agentService.run(notebookId, task, provider, sessionId);
            return ResponseEntity.ok(Map.of("output", output, "provider", provider, "success", true));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage(),
                    "success", false));
        }
    }

    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody Map<String, String> body) {
        String notebookId = body.get("notebookId");
        String provider   = body.getOrDefault("provider", "claude");
        String target     = body.getOrDefault("target", "project");
        if (notebookId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "notebookId required"));
        }
        try {
            Map<String, Object> result = new java.util.LinkedHashMap<>(
                    agentService.deploy(notebookId, provider, target));
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage(),
                    "success", false));
        }
    }

    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@RequestBody Map<String, String> body) {
        String notebookId = body.get("notebookId");
        String provider   = body.getOrDefault("provider", "claude");
        if (notebookId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "notebookId required"));
        }
        try {
            String path = agentService.export(notebookId, provider);
            return ResponseEntity.ok(Map.of("path", path, "success", true));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage(),
                    "success", false));
        }
    }

    @GetMapping("/deployments")
    public ResponseEntity<List<Map<String, Object>>> deployments() {
        return ResponseEntity.ok(agentService.deployments());
    }

    @PostMapping("/undeploy")
    public ResponseEntity<Map<String, Object>> undeploy(@RequestBody Map<String, String> body) {
        String id     = body.get("id");
        String target = body.getOrDefault("target", "project");
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "id required"));
        }
        int removed = agentService.undeploy(id, target);
        return ResponseEntity.ok(Map.of("success", true, "removed", removed, "target", target));
    }
}
