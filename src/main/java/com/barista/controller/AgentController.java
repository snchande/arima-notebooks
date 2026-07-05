package com.barista.controller;

import com.barista.model.Notebook;
import com.barista.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoints for authoring, running, and exporting agents & skills.
 *
 *   POST /api/agents/create   - create a new agent/skill notebook (pre-seeded)
 *   POST /api/agents/run      - run the agent/skill against a task (streams via STOMP)
 *   POST /api/agents/export   - write the provider's native files
 *   GET  /api/agents/providers- provider availability
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
}
