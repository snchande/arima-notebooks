package com.barista.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.barista.model.Cell;
import com.barista.model.ExecutionResult;
import com.barista.model.Notebook;
import com.barista.service.AgentService;
import com.barista.service.NotebookService;
import com.barista.service.OrchestrationService;
import com.barista.service.PackageService;
import com.barista.service.UserService;
import com.barista.shell.JShellManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP (Model Context Protocol) server implementation over HTTP + SSE transport.
 *
 * Implements JSON-RPC 2.0 messages as per the MCP spec.
 *
 * Transport endpoints:
 *   GET  /api/mcp/sse       — SSE stream for server → client messages
 *   POST /api/mcp/messages  — client → server messages (JSON body)
 *
 * Supported JSON-RPC methods:
 *   initialize               — return server info and capabilities
 *   notifications/initialized — no-op acknowledgement
 *   tools/list               — enumerate available tools
 *   tools/call               — invoke a tool by name
 *   ping                     — health check
 *
 * Available tools:
 *   barista_execute_code       — Execute Java code in a JShell session
 *   barista_list_notebooks     — List all available notebooks
 *   barista_read_notebook      — Read all cells from a notebook
 *   barista_run_pipeline       — Execute a pipeline cell with dependency resolution
 *   barista_search_cells       — Search for cells by anchor name or source content
 *   barista_load_module        — Load a named cell module from a notebook into a session
 *   barista_create_notebook    — Create a new notebook, optionally pre-populated with cells
 *   barista_append_cell        — Append a new cell to an existing notebook and optionally execute it
 *   barista_list_agents        — List agent & skill definitions (user notebooks + built-in samples)
 *   barista_run_agent          — Run an agent/skill against a task and return its response
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final String SERVER_NAME    = "arima-notebooks";
    private static final String SERVER_VERSION = "1.0.0";

    // Active SSE emitters keyed by MCP sessionId
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private final NotebookService notebookService;
    private final JShellManager jShellManager;
    private final OrchestrationService orchestrationService;
    private final PackageService packageService;
    private final UserService userService;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    public McpController(NotebookService notebookService,
                          JShellManager jShellManager,
                          OrchestrationService orchestrationService,
                          PackageService packageService,
                          UserService userService,
                          AgentService agentService,
                          ObjectMapper objectMapper) {
        this.notebookService = notebookService;
        this.jShellManager = jShellManager;
        this.orchestrationService = orchestrationService;
        this.packageService = packageService;
        this.userService = userService;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    // ── SSE endpoint ──────────────────────────────────────────────────────

    /**
     * Opens an SSE stream for a given MCP sessionId.
     * On connect, sends the MCP "endpoint" event so the client knows where to POST.
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(@RequestParam(defaultValue = "default") String sessionId,
                          HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.put(sessionId, emitter);

        // MCP protocol: send the full absolute message endpoint URL so any client can reach it
        String base = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        try {
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data(base + "/api/mcp/messages?sessionId=" + sessionId));
        } catch (IOException e) {
            log.warn("Failed to send initial endpoint event to MCP session {}: {}", sessionId, e.getMessage());
            emitter.complete();
        }

        emitter.onCompletion(() -> {
            emitters.remove(sessionId);
            log.debug("MCP SSE session completed: {}", sessionId);
        });
        emitter.onError(e -> {
            emitters.remove(sessionId);
            log.debug("MCP SSE session error for {}: {}", sessionId, e.getMessage());
        });
        emitter.onTimeout(() -> emitters.remove(sessionId));

        log.info("MCP SSE session opened: {}", sessionId);
        return emitter;
    }

    // ── Message endpoint ──────────────────────────────────────────────────

    /**
     * Handles inbound JSON-RPC 2.0 messages from MCP clients.
     * Dispatches to the appropriate handler based on the "method" field.
     */
    @PostMapping("/messages")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> handleMessage(
            @RequestBody Map<String, Object> request,
            @RequestParam(defaultValue = "default") String sessionId) {

        String method = (String) request.get("method");
        Object id     = request.get("id");
        Map<String, Object> params = request.containsKey("params")
                ? (Map<String, Object>) request.get("params")
                : Map.of();

        log.debug("MCP message: method={}, id={}, session={}", method, id, sessionId);

        try {
            Object result = dispatch(method, params, sessionId);

            // Notifications have no id and return no response body
            if (result == null && isNotification(method)) {
                return ResponseEntity.accepted().<Map<String, Object>>build();
            }

            return ResponseEntity.ok(jsonRpcSuccess(id, result != null ? result : Map.of()));

        } catch (McpException e) {
            log.warn("MCP error for method '{}': code={}, msg={}", method, e.code(), e.getMessage());
            return ResponseEntity.ok(jsonRpcError(id, e.code(), e.getMessage()));
        } catch (Exception e) {
            log.error("MCP internal error for method '{}': {}", method, e.getMessage(), e);
            return ResponseEntity.ok(jsonRpcError(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    // ── Dispatcher ────────────────────────────────────────────────────────

    private Object dispatch(String method, Map<String, Object> params, String sessionId)
            throws McpException {
        if (method == null) {
            throw new McpException(-32600, "Missing 'method' field");
        }
        return switch (method) {
            case "initialize"                -> handleInitialize(params);
            case "notifications/initialized" -> null; // no-op notification
            case "ping"                      -> Map.of(); // empty result
            case "tools/list"               -> handleToolsList();
            case "tools/call"               -> handleToolsCall(params, sessionId);
            default                          -> throw new McpException(-32601,
                    "Method not found: " + method);
        };
    }

    private boolean isNotification(String method) {
        return method != null && method.startsWith("notifications/");
    }

    // ── MCP method handlers ───────────────────────────────────────────────

    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        return Map.of(
            "protocolVersion", "2024-11-05",
            "serverInfo", Map.of(
                "name",    SERVER_NAME,
                "version", SERVER_VERSION
            ),
            "capabilities", Map.of(
                "tools", Map.of()
            )
        );
    }

    private Map<String, Object> handleToolsList() {
        List<Map<String, Object>> tools = List.of(
            toolDef("barista_execute_code",
                "Execute Java code in a Arima JShell session and return output",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "code",    Map.of("type", "string",  "description", "Java code to execute"),
                        "session", Map.of("type", "string",  "description", "Session ID (default: mcp-session)")
                    ),
                    "required", List.of("code")
                )
            ),
            toolDef("barista_list_notebooks",
                "List all available notebooks in Arima",
                Map.of("type", "object", "properties", Map.of())
            ),
            toolDef("barista_read_notebook",
                "Read all cells from a notebook including source code and anchors",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "notebookId", Map.of("type", "string", "description", "The notebook ID")
                    ),
                    "required", List.of("notebookId")
                )
            ),
            toolDef("barista_run_pipeline",
                "Execute a pipeline cell in a notebook, resolving all cell dependencies in order",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "notebookId", Map.of("type", "string", "description", "The notebook ID"),
                        "cellId",     Map.of("type", "string", "description", "The pipeline cell ID"),
                        "session",    Map.of("type", "string", "description", "Session ID (optional)")
                    ),
                    "required", List.of("notebookId", "cellId")
                )
            ),
            toolDef("barista_search_cells",
                "Search for cells by anchor name across all notebooks",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of("type", "string",
                            "description", "Search query — matches anchor names and cell source content")
                    ),
                    "required", List.of("query")
                )
            ),
            toolDef("barista_load_module",
                "Load a named cell module from a notebook into an active session",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "notebookRef", Map.of("type", "string",
                            "description", "Module reference in format 'notebookId/anchorName'"),
                        "session",     Map.of("type", "string", "description", "Session ID (optional)")
                    ),
                    "required", List.of("notebookRef")
                )
            ),
            toolDef("barista_create_notebook",
                "Create a new Arima notebook, optionally pre-populated with cells",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name",        Map.of("type", "string", "description", "Notebook name"),
                        "description", Map.of("type", "string", "description", "Optional description"),
                        "cells",       Map.of("type", "array",
                                        "description", "Optional list of cells: [{type, source, anchor?}]",
                                        "items", Map.of("type", "object"))
                    ),
                    "required", List.of("name")
                )
            ),
            toolDef("barista_append_cell",
                "Append a new cell to an existing notebook and optionally execute it",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "notebookId", Map.of("type", "string", "description", "Target notebook ID"),
                        "type",       Map.of("type", "string", "description", "Cell type: CODE or MARKDOWN (default: CODE)"),
                        "source",     Map.of("type", "string", "description", "Cell source code or markdown content"),
                        "anchor",     Map.of("type", "string", "description", "Optional anchor name for the cell"),
                        "execute",    Map.of("type", "boolean", "description", "Execute cell after adding (default: false)"),
                        "session",    Map.of("type", "string", "description", "Session ID for execution (optional)")
                    ),
                    "required", List.of("notebookId", "source")
                )
            ),
            toolDef("barista_list_agents",
                "List agent & skill definitions available in Arima (the user's own agent notebooks "
                    + "plus built-in samples). Each entry gives the id to pass to barista_run_agent.",
                Map.of("type", "object", "properties", Map.of())
            ),
            toolDef("barista_run_agent",
                "Run an Arima agent or skill against a task and return its response. The agentId is a "
                    + "notebook id or a built-in sample id (e.g. agent-101). Compose agents from any MCP client.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "agentId",  Map.of("type", "string", "description", "Agent/skill id (notebook id or sample, e.g. agent-201)"),
                        "task",     Map.of("type", "string", "description", "The task or input to give the agent"),
                        "provider", Map.of("type", "string", "description", "AI provider key (default: the agent's own, else claude)")
                    ),
                    "required", List.of("agentId", "task")
                )
            )
        );
        return Map.of("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Map<String, Object> params, String mcpSessionId)
            throws McpException {

        String toolName = (String) params.get("name");
        if (toolName == null) throw new McpException(-32602, "Missing 'name' in tools/call params");

        Map<String, Object> args = params.containsKey("arguments")
                ? (Map<String, Object>) params.get("arguments")
                : Map.of();

        String text = switch (toolName) {
            case "barista_execute_code"   -> toolExecuteCode(args);
            case "barista_list_notebooks" -> toolListNotebooks();
            case "barista_read_notebook"  -> toolReadNotebook(args);
            case "barista_run_pipeline"   -> toolRunPipeline(args);
            case "barista_search_cells"   -> toolSearchCells(args);
            case "barista_load_module"    -> toolLoadModule(args);
            case "barista_create_notebook" -> toolCreateNotebook(args);
            case "barista_append_cell"    -> toolAppendCell(args);
            case "barista_list_agents"    -> toolListAgents();
            case "barista_run_agent"      -> toolRunAgent(args);
            default -> throw new McpException(-32602, "Unknown tool: " + toolName);
        };

        return Map.of(
            "content", List.of(Map.of("type", "text", "text", text))
        );
    }

    // ── Tool implementations ──────────────────────────────────────────────

    private String toolExecuteCode(Map<String, Object> args) throws McpException {
        String code = (String) args.get("code");
        if (code == null || code.isBlank()) {
            throw new McpException(-32602, "Parameter 'code' is required");
        }
        String sessionId = args.containsKey("session")
                ? (String) args.get("session") : "mcp-session";

        // Ensure session exists and packages are applied
        if (!jShellManager.hasSession(sessionId)) {
            jShellManager.getOrCreateSession(sessionId);
            packageService.applyPackagesToSession(sessionId);
        }

        ExecutionResult result = jShellManager.execute(sessionId, code, null);

        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(result.getSessionId()).append("\n");
        sb.append("Status: ").append(result.isSuccess() ? "SUCCESS" : "ERROR").append("\n");
        if (result.getOutput() != null && !result.getOutput().isBlank()) {
            sb.append("Output:\n").append(result.getOutput()).append("\n");
        }
        if (result.getReturnValue() != null && !result.getReturnValue().isBlank()) {
            sb.append("Return value: ").append(result.getReturnValue()).append("\n");
        }
        if (!result.isSuccess() && result.getError() != null && !result.getError().isBlank()) {
            sb.append("Error:\n").append(result.getError()).append("\n");
        }
        sb.append("Execution time: ").append(result.getExecutionTimeMs()).append("ms");
        return sb.toString();
    }

    private String toolListNotebooks() {
        List<Map<String, Object>> notebooks = notebookService.listNotebooks(userService.getLocalUserId());
        if (notebooks.isEmpty()) {
            return "No notebooks found.";
        }
        StringBuilder sb = new StringBuilder("Available notebooks:\n\n");
        for (Map<String, Object> nb : notebooks) {
            sb.append("ID:       ").append(nb.get("id")).append("\n");
            sb.append("Name:     ").append(nb.get("name")).append("\n");
            if (nb.get("description") != null) {
                sb.append("Desc:     ").append(nb.get("description")).append("\n");
            }
            sb.append("Cells:    ").append(nb.get("cellCount")).append("\n");
            sb.append("Modified: ").append(nb.get("modified")).append("\n");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String toolReadNotebook(Map<String, Object> args) throws McpException {
        String notebookId = (String) args.get("notebookId");
        if (notebookId == null || notebookId.isBlank()) {
            throw new McpException(-32602, "Parameter 'notebookId' is required");
        }

        Notebook notebook = notebookService.getNotebook(notebookId, userService.getLocalUserId()).orElse(null);
        if (notebook == null) {
            throw new McpException(-32602, "Notebook not found: " + notebookId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Notebook: ").append(notebook.getName()).append("\n");
        sb.append("ID:       ").append(notebook.getId()).append("\n");
        if (notebook.getDescription() != null) {
            sb.append("Desc:     ").append(notebook.getDescription()).append("\n");
        }
        sb.append("Cells:    ").append(notebook.getCells().size()).append("\n\n");

        for (int i = 0; i < notebook.getCells().size(); i++) {
            Cell cell = notebook.getCells().get(i);
            sb.append("--- Cell ").append(i + 1).append(" ---\n");
            sb.append("ID:   ").append(cell.getId()).append("\n");
            sb.append("Type: ").append(cell.getType()).append("\n");
            if (cell.getAnchor() != null && !cell.getAnchor().isBlank()) {
                sb.append("Anchor: ").append(cell.getAnchor()).append("\n");
            }
            if (!cell.getDependsOn().isEmpty()) {
                sb.append("Depends: ").append(String.join(", ", cell.getDependsOn())).append("\n");
            }
            if (!cell.getPipelineSteps().isEmpty()) {
                sb.append("Steps: ").append(String.join(", ", cell.getPipelineSteps())).append("\n");
            }
            sb.append("Source:\n").append(cell.getSource()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private String toolRunPipeline(Map<String, Object> args) throws McpException {
        String notebookId = (String) args.get("notebookId");
        String cellId     = (String) args.get("cellId");
        if (notebookId == null || notebookId.isBlank()) {
            throw new McpException(-32602, "Parameter 'notebookId' is required");
        }
        if (cellId == null || cellId.isBlank()) {
            throw new McpException(-32602, "Parameter 'cellId' is required");
        }
        String sessionId = args.containsKey("session")
                ? (String) args.get("session") : "mcp-session";

        Notebook notebook = notebookService.getNotebook(notebookId, userService.getLocalUserId()).orElse(null);
        if (notebook == null) {
            throw new McpException(-32602, "Notebook not found: " + notebookId);
        }

        if (!jShellManager.hasSession(sessionId)) {
            jShellManager.getOrCreateSession(sessionId);
            packageService.applyPackagesToSession(sessionId);
        }

        OrchestrationService.PipelineResult result =
                orchestrationService.executePipeline(notebook, cellId, sessionId);

        return formatPipelineResult(result);
    }

    private String toolSearchCells(Map<String, Object> args) throws McpException {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            throw new McpException(-32602, "Parameter 'query' is required");
        }
        String lowerQuery = query.toLowerCase();

        List<Map<String, Object>> allNotebooks = notebookService.listNotebooks(userService.getLocalUserId());
        StringBuilder sb = new StringBuilder();
        int matchCount = 0;

        for (Map<String, Object> nbMeta : allNotebooks) {
            String nbId = (String) nbMeta.get("id");
            Notebook notebook = notebookService.getNotebook(nbId, userService.getLocalUserId()).orElse(null);
            if (notebook == null) continue;

            for (Cell cell : notebook.getCells()) {
                boolean anchorMatch = cell.getAnchor() != null
                        && cell.getAnchor().toLowerCase().contains(lowerQuery);
                boolean sourceMatch = cell.getSource() != null
                        && cell.getSource().toLowerCase().contains(lowerQuery);

                if (anchorMatch || sourceMatch) {
                    matchCount++;
                    sb.append("Notebook: ").append(notebook.getName())
                      .append(" (").append(nbId).append(")\n");
                    sb.append("Cell ID:  ").append(cell.getId()).append("\n");
                    sb.append("Type:     ").append(cell.getType()).append("\n");
                    if (cell.getAnchor() != null) {
                        sb.append("Anchor:   ").append(cell.getAnchor()).append("\n");
                    }
                    // Show a short excerpt of the source
                    String src = cell.getSource();
                    if (src != null && !src.isBlank()) {
                        String excerpt = src.length() > 200 ? src.substring(0, 200) + "..." : src;
                        sb.append("Source:\n").append(excerpt).append("\n");
                    }
                    sb.append("\n");
                }
            }
        }

        if (matchCount == 0) {
            return "No cells found matching query: " + query;
        }
        return "Found " + matchCount + " cell(s) matching '" + query + "':\n\n"
                + sb.toString().stripTrailing();
    }

    private String toolLoadModule(Map<String, Object> args) throws McpException {
        String notebookRef = (String) args.get("notebookRef");
        if (notebookRef == null || notebookRef.isBlank()) {
            throw new McpException(-32602, "Parameter 'notebookRef' is required (format: notebookId/anchorName)");
        }
        String sessionId = args.containsKey("session")
                ? (String) args.get("session") : "mcp-session";

        // Parse "notebookId/anchorName" format
        int slash = notebookRef.indexOf('/');
        if (slash < 1 || slash >= notebookRef.length() - 1) {
            throw new McpException(-32602,
                    "Invalid notebookRef format. Expected 'notebookId/anchorName', got: " + notebookRef);
        }

        if (!jShellManager.hasSession(sessionId)) {
            jShellManager.getOrCreateSession(sessionId);
            packageService.applyPackagesToSession(sessionId);
        }

        // Delegate to OrchestrationService cross-notebook loading by constructing
        // a minimal notebook and running a single-cell pipeline that depends on the module.
        // We do this by directly using the execute-with-deps pathway via a synthetic notebook.
        // Simpler: create a throwaway cell that depends on the cross-notebook ref and run it.
        String syntheticSource = "//@ anchor: __mcp_load__\n"
                + "//@ depends: notebook:" + notebookRef + "\n"
                + "// module loaded";

        com.barista.model.Cell syntheticCell = new com.barista.model.Cell();
        syntheticCell.setId("__mcp_load_cell__");
        syntheticCell.setType(com.barista.model.CellType.CODE);
        syntheticCell.setAnchor("__mcp_load__");
        syntheticCell.setDependsOn(List.of("notebook:" + notebookRef));
        syntheticCell.setSource(syntheticSource);

        Notebook syntheticNotebook = new Notebook();
        syntheticNotebook.setId("__mcp_synthetic__");
        syntheticNotebook.setName("MCP Synthetic");
        syntheticNotebook.setCells(List.of(syntheticCell));

        OrchestrationService.PipelineResult result =
                orchestrationService.executeWithDependencies(syntheticNotebook,
                        "__mcp_load_cell__", sessionId);

        if (result.success()) {
            return "Module '" + notebookRef + "' loaded successfully into session '" + sessionId + "'.\n"
                    + formatPipelineResult(result);
        } else {
            return "Failed to load module '" + notebookRef + "':\n" + result.error();
        }
    }

    @SuppressWarnings("unchecked")
    private String toolCreateNotebook(Map<String, Object> args) throws McpException {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) {
            throw new McpException(-32602, "Parameter 'name' is required");
        }
        String description = args.containsKey("description") ? (String) args.get("description") : "";

        // Create notebook via REST-level service
        com.barista.model.Notebook notebook = notebookService.createNotebook(name, userService.getLocalUserId());
        if (description != null && !description.isBlank()) {
            notebook.setDescription(description);
        }

        // Add any cells provided in the request
        List<Map<String, Object>> cellDefs = args.containsKey("cells")
                ? (List<Map<String, Object>>) args.get("cells") : List.of();

        for (Map<String, Object> cellDef : cellDefs) {
            com.barista.model.Cell cell = new com.barista.model.Cell();
            cell.setId("cell-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 9999));
            String cellType = cellDef.containsKey("type") ? (String) cellDef.get("type") : "CODE";
            cell.setType("MARKDOWN".equalsIgnoreCase(cellType)
                    ? com.barista.model.CellType.MARKDOWN : com.barista.model.CellType.CODE);
            cell.setMode("jshell");
            cell.setSource(cellDef.containsKey("source") ? (String) cellDef.get("source") : "");
            if (cellDef.containsKey("anchor") && cellDef.get("anchor") != null) {
                cell.setAnchor((String) cellDef.get("anchor"));
            }
            notebook.getCells().add(cell);
            try { Thread.sleep(1); } catch (InterruptedException ignored) {} // ensure unique IDs
        }

        notebookService.saveNotebook(notebook, userService.getLocalUserId());

        return "Notebook created successfully.\n"
                + "ID:    " + notebook.getId() + "\n"
                + "Name:  " + notebook.getName() + "\n"
                + "Cells: " + notebook.getCells().size();
    }

    @SuppressWarnings("unchecked")
    private String toolAppendCell(Map<String, Object> args) throws McpException {
        String notebookId = (String) args.get("notebookId");
        if (notebookId == null || notebookId.isBlank()) {
            throw new McpException(-32602, "Parameter 'notebookId' is required");
        }
        String source = (String) args.get("source");
        if (source == null) source = "";

        com.barista.model.Notebook notebook = notebookService.getNotebook(notebookId, userService.getLocalUserId()).orElse(null);
        if (notebook == null) {
            throw new McpException(-32602, "Notebook not found: " + notebookId);
        }

        String typeStr = args.containsKey("type") ? (String) args.get("type") : "CODE";
        boolean isMarkdown = "MARKDOWN".equalsIgnoreCase(typeStr);

        com.barista.model.Cell cell = new com.barista.model.Cell();
        String cellId = "cell-" + System.currentTimeMillis();
        cell.setId(cellId);
        cell.setType(isMarkdown ? com.barista.model.CellType.MARKDOWN : com.barista.model.CellType.CODE);
        cell.setMode("jshell");
        cell.setSource(source);
        if (args.containsKey("anchor") && args.get("anchor") != null) {
            cell.setAnchor((String) args.get("anchor"));
        }

        notebook.getCells().add(cell);
        notebookService.saveNotebook(notebook, userService.getLocalUserId());

        StringBuilder sb = new StringBuilder();
        sb.append("Cell appended to notebook '").append(notebook.getName()).append("'.\n");
        sb.append("Cell ID: ").append(cellId).append("\n");
        sb.append("Type:    ").append(cell.getType()).append("\n");
        sb.append("Total cells: ").append(notebook.getCells().size()).append("\n");

        // Optionally execute
        boolean execute = args.containsKey("execute") && Boolean.TRUE.equals(args.get("execute"));
        if (execute && !isMarkdown) {
            String sessionId = args.containsKey("session") ? (String) args.get("session") : "mcp-session";
            if (!jShellManager.hasSession(sessionId)) {
                jShellManager.getOrCreateSession(sessionId);
                packageService.applyPackagesToSession(sessionId);
            }
            ExecutionResult result = jShellManager.execute(sessionId, source, cellId);
            sb.append("\nExecution result:\n");
            sb.append("Status: ").append(result.isSuccess() ? "SUCCESS" : "ERROR").append("\n");
            if (result.getOutput() != null && !result.getOutput().isBlank()) {
                sb.append("Output:\n").append(result.getOutput()).append("\n");
            }
            if (!result.isSuccess() && result.getError() != null) {
                sb.append("Error:\n").append(result.getError()).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    @SuppressWarnings("unchecked")
    private String toolListAgents() {
        List<Map<String, Object>> agents = agentService.list();
        if (agents.isEmpty()) {
            return "No agents or skills found. Create one via the Agents tab or POST /api/agents/create.";
        }
        StringBuilder sb = new StringBuilder("Available agents & skills:\n\n");
        for (Map<String, Object> a : agents) {
            sb.append("ID:       ").append(a.get("id")).append("\n");
            sb.append("Name:     ").append(a.get("name")).append("\n");
            sb.append("Kind:     ").append(a.get("kind")).append("\n");
            if (a.get("description") != null) {
                sb.append("Desc:     ").append(a.get("description")).append("\n");
            }
            sb.append("Provider: ").append(a.get("provider")).append("\n");
            Object tools = a.get("tools");
            if (tools instanceof List<?> l && !l.isEmpty()) {
                sb.append("Tools:    ").append(l.stream().map(String::valueOf).collect(Collectors.joining(", "))).append("\n");
            }
            sb.append("Source:   ").append(a.get("source")).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private String toolRunAgent(Map<String, Object> args) throws McpException {
        String agentId = (String) args.get("agentId");
        if (agentId == null || agentId.isBlank()) {
            throw new McpException(-32602, "Parameter 'agentId' is required");
        }
        String task = args.containsKey("task") ? (String) args.get("task") : "";
        if (task == null || task.isBlank()) {
            throw new McpException(-32602, "Parameter 'task' is required");
        }
        String provider = args.containsKey("provider") ? (String) args.get("provider") : null;

        try {
            // sessionId null → AgentService streams to the notebook's own topic (no MCP subscriber),
            // and returns the final response, which is what we hand back to the MCP client.
            String output = agentService.run(agentId, task, provider, null);
            return "Agent: " + agentId + "\nStatus: SUCCESS\n\n" + output;
        } catch (Exception e) {
            throw new McpException(-32603, "Agent run failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String formatPipelineResult(OrchestrationService.PipelineResult result) {
        StringBuilder sb = new StringBuilder();
        if (!result.success()) {
            sb.append("PIPELINE FAILED\n");
            if (result.error() != null) sb.append("Error: ").append(result.error()).append("\n");
        } else {
            sb.append("PIPELINE SUCCESS\n");
        }
        sb.append("Steps executed: ").append(result.results().size()).append("\n\n");
        for (int i = 0; i < result.results().size(); i++) {
            ExecutionResult r = result.results().get(i);
            sb.append("Step ").append(i + 1);
            if (r.getCellId() != null) sb.append(" (cell: ").append(r.getCellId()).append(")");
            sb.append(": ").append(r.isSuccess() ? "OK" : "FAILED").append("\n");
            if (r.getOutput() != null && !r.getOutput().isBlank()) {
                sb.append("  Output: ").append(r.getOutput().stripTrailing()).append("\n");
            }
            if (r.getReturnValue() != null && !r.getReturnValue().isBlank()) {
                sb.append("  Return: ").append(r.getReturnValue()).append("\n");
            }
            if (!r.isSuccess() && r.getError() != null && !r.getError().isBlank()) {
                sb.append("  Error: ").append(r.getError()).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private static Map<String, Object> toolDef(String name, String description,
                                                Map<String, Object> inputSchema) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    private static Map<String, Object> jsonRpcSuccess(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> jsonRpcError(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message != null ? message : "Unknown error"));
        return response;
    }

    // ── Exception type ─────────────────────────────────────────────────────

    private static class McpException extends Exception {
        private final int errorCode;

        McpException(int code, String message) {
            super(message);
            this.errorCode = code;
        }

        int code() { return errorCode; }
    }
}
