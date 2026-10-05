package com.barista.controller;

import com.barista.model.Deployment;
import com.barista.model.Notebook;
import com.barista.service.ConnectorService;
import com.barista.service.DeploymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for connectors — the external MCP servers Arima attaches to.
 *
 *   GET  /api/connectors/list      - list connector definitions
 *   POST /api/connectors/create    - create a new connector notebook
 *   POST /api/connectors/probe     - connect and list the server's tools
 *   POST /api/connectors/call      - call one of the server's tools
 *   POST /api/connectors/deploy    - merge the connector into a target's .mcp.json
 *   POST /api/connectors/undeploy  - remove just this connector's entry from that file
 */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final ConnectorService connectorService;
    private final DeploymentService deployments;

    public ConnectorController(ConnectorService connectorService, DeploymentService deployments) {
        this.connectorService = connectorService;
        this.deployments = deployments;
    }

    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(connectorService.list());
    }

    @PostMapping("/create")
    public ResponseEntity<Notebook> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String transport = body.getOrDefault("transport", "stdio");
        return ResponseEntity.ok(connectorService.create(name, transport));
    }

    @PostMapping("/probe")
    public ResponseEntity<Map<String, Object>> probe(@RequestBody Map<String, String> body) {
        String connectorId = body.get("connectorId");
        if (connectorId == null || connectorId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "connectorId required"));
        }
        try {
            return ResponseEntity.ok(connectorService.probe(connectorId));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false,
                    "error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    @PostMapping("/call")
    public ResponseEntity<Map<String, Object>> call(@RequestBody Map<String, Object> body) {
        String connectorId = (String) body.get("connectorId");
        String tool = (String) body.get("tool");
        if (connectorId == null || tool == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "connectorId and tool required"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = body.get("args") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        try {
            String output = connectorService.callTool(connectorId, tool, args);
            return ResponseEntity.ok(Map.of("success", true, "output", output, "tool", tool));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false,
                    "error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody Map<String, String> body) {
        String connectorId = body.get("connectorId");
        String target = body.getOrDefault("target", "project");
        if (connectorId == null || connectorId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "connectorId required"));
        }
        try {
            Deployment d = connectorService.deploy(connectorId, target);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("slug", d.getSlug());
            out.put("target", d.getTarget());
            out.put("targetLabel", deployments.labelFor(d.getTarget()));
            out.put("path", deployments.rootFor(d.getTarget()).resolve(".mcp.json")
                    .toAbsolutePath().normalize().toString());
            out.put("deployedAt", d.getDeployedAt());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false,
                    "error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    @PostMapping("/undeploy")
    public ResponseEntity<Map<String, Object>> undeploy(@RequestBody Map<String, String> body) {
        String connectorId = body.get("connectorId");
        String target = body.getOrDefault("target", "project");
        if (connectorId == null || connectorId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "connectorId required"));
        }
        try {
            int removed = connectorService.undeploy(connectorId, target);
            return ResponseEntity.ok(Map.of("success", true, "removed", removed, "target", target));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false,
                    "error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
}
