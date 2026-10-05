package com.barista.controller;

import com.barista.model.ExecutionResult;
import com.barista.model.Notebook;
import com.barista.model.ToolSpec;
import com.barista.service.ToolService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for authoring and calling tools.
 *
 *   GET  /api/tools/list          - list tool definitions (user notebooks + built-in samples)
 *   GET  /api/tools/modes         - the execution modes a tool body may be written in
 *   GET  /api/tools/{id}/schema   - JSON-Schema projection of the tool's parameters
 *   POST /api/tools/create        - create a new tool notebook (pre-seeded and runnable)
 *   POST /api/tools/invoke        - call the tool with arguments and return its output
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolService toolService;

    public ToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(toolService.list());
    }

    @GetMapping("/modes")
    public ResponseEntity<List<String>> modes() {
        return ResponseEntity.ok(ToolService.MODES);
    }

    @GetMapping("/{id}/schema")
    public ResponseEntity<Map<String, Object>> schema(@PathVariable String id) {
        return toolService.spec(id)
                .<ResponseEntity<Map<String, Object>>>map(t -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("name", "arima_" + t.slug().replace('-', '_'));
                    out.put("description", t.description());
                    out.put("mode", t.mode());
                    out.put("inputSchema", t.inputSchema());
                    return ResponseEntity.ok(out);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Tool not found: " + id)));
    }

    @PostMapping("/create")
    public ResponseEntity<Notebook> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String mode = body.getOrDefault("mode", "python");
        return ResponseEntity.ok(toolService.create(name, mode));
    }

    @PostMapping("/invoke")
    public ResponseEntity<Map<String, Object>> invoke(@RequestBody Map<String, Object> body) {
        String toolId = (String) body.get("toolId");
        if (toolId == null || toolId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "toolId required"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = body.get("args") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String sessionId = (String) body.get("sessionId");

        try {
            ToolSpec tool = toolService.spec(toolId)
                    .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolId));
            ExecutionResult r = toolService.invoke(tool, args, sessionId);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", r.isSuccess());
            out.put("output", r.getOutput());
            out.put("returnValue", r.getReturnValue());
            out.put("error", r.getError());
            out.put("executionTimeMs", r.getExecutionTimeMs());
            out.put("tool", tool.slug());
            out.put("mode", tool.mode());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
}
