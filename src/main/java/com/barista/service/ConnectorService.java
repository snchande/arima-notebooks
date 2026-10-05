package com.barista.service;

import com.barista.model.AgentSpec;
import com.barista.model.Cell;
import com.barista.model.CellType;
import com.barista.model.ConnectorSpec;
import com.barista.model.Deployment;
import com.barista.model.Notebook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Connectors — the external MCP servers Arima attaches to.
 *
 * {@link com.barista.controller.McpController} makes Arima a tool server for other clients; this is
 * the other direction. A connector notebook ({@code metadata.kind = "connector"}) names a server,
 * {@link McpClient} speaks to it, and the tools it offers become available to Arima's agents and to
 * any agentic CLI you deploy the connector into.
 *
 * <p><b>The network rule.</b> AGENTS.md &sect;2.3 forbids adding outbound hosts. A connector does
 * not add one: a {@code stdio} connector is a local subprocess, and an {@code sse} connector to
 * loopback never leaves the machine. A connector pointed at a remote host <em>would</em> leave it,
 * so every such call blocks on {@link ApprovalService} first — the same gate that guards
 * non-local access — and is refused if the user does not approve it. The host is the user's own
 * choice, made per connection, never a default compiled into Arima.
 */
@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final NotebookService notebookService;
    private final UserService userService;
    private final McpClient mcpClient;
    private final ApprovalService approvals;
    private final DeploymentService deployments;

    public ConnectorService(NotebookService notebookService,
                            UserService userService,
                            McpClient mcpClient,
                            ApprovalService approvals,
                            DeploymentService deployments) {
        this.notebookService = notebookService;
        this.userService = userService;
        this.mcpClient = mcpClient;
        this.approvals = approvals;
        this.deployments = deployments;
    }

    // -- Catalog ---------------------------------------------------------------

    /**
     * Every connector definition available to the current user. Each entry is the card projection:
     * {@code id, name, slug, description, transport, endpoint, remote, enabled, source, deployedTo}.
     */
    public List<Map<String, Object>> list() {
        String userId = userService.getCurrentUser().getId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> meta : notebookService.listNotebooks(userId)) {
            if (isConnectorMeta(meta)) spec(str(meta.get("id"))).ifPresent(c -> out.add(card(c, "mine")));
        }
        for (Map<String, Object> meta : notebookService.listTutorials()) {
            if (isConnectorMeta(meta)) spec(str(meta.get("id"))).ifPresent(c -> out.add(card(c, "sample")));
        }
        return out;
    }

    /** The spec for one connector, by notebook id. */
    public Optional<ConnectorSpec> spec(String connectorId) {
        return load(connectorId).filter(this::isConnector).map(this::toSpec);
    }

    private Map<String, Object> card(ConnectorSpec c, String source) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", c.id());
        card.put("name", c.name());
        card.put("slug", c.slug());
        card.put("description", c.description());
        card.put("transport", c.transport());
        card.put("endpoint", c.endpoint());
        card.put("remote", c.isRemote());
        card.put("enabled", c.enabled());
        card.put("source", source);
        card.put("deployedTo", DeploymentService.TARGETS.stream()
                .filter(t -> deployments.isDeployed(c.id(), t))
                .collect(Collectors.toList()));
        return card;
    }

    // -- Authoring -------------------------------------------------------------

    /** Create a connector notebook, pre-seeded for the chosen transport. */
    public Notebook create(String name, String transport) {
        String userId = userService.getCurrentUser().getId();
        String t = ConnectorSpec.transportOf(transport);
        String nbName = (name == null || name.isBlank()) ? "New Connector" : name.trim();

        Notebook nb = notebookService.createNotebook(nbName, userId);
        nb.setDescription("Which server this connects to, and what its tools are for.");
        nb.getMetadata().put("kind", "connector");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("transport", t);
        if (ConnectorSpec.SSE.equals(t)) {
            meta.put("url", "http://127.0.0.1:3000/sse");
            meta.put("command", new ArrayList<String>());
        } else {
            meta.put("command", List.of("npx", "-y", "@modelcontextprotocol/server-everything"));
            meta.put("url", "");
        }
        meta.put("env", new LinkedHashMap<String, String>());
        meta.put("enabled", true);
        nb.getMetadata().put("connector", meta);

        Cell notes = new Cell();
        notes.setType(CellType.MARKDOWN);
        notes.setSource("# " + nbName + "\n\nWhat this server provides, and anything needed to run it "
                + "(an API key in the environment, a path argument, a login).\n\n"
                + "Press **Probe** in the banner to connect and list the tools it offers. These notes "
                + "stay here — they are never sent to the server.");

        nb.getCells().clear();
        nb.getCells().add(notes);
        return notebookService.saveNotebook(nb, userId);
    }

    // -- Probing ---------------------------------------------------------------

    /**
     * Connect to the server and list its tools. A remote endpoint blocks on the approval gate
     * first; a denial is reported as a failure rather than silently proceeding.
     *
     * @return {@code {success, serverName, serverVersion, tools[], transport, endpoint}}
     */
    public Map<String, Object> probe(String connectorId) {
        ConnectorSpec c = spec(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found: " + connectorId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transport", c.transport());
        out.put("endpoint", c.endpoint());
        out.put("remote", c.isRemote());

        if (!gate(c, "probe")) {
            out.put("success", false);
            out.put("error", "Reaching " + c.endpoint() + " was not approved.");
            return out;
        }
        try {
            McpClient.Probe p = mcpClient.probe(c);
            out.put("success", true);
            out.put("serverName", p.serverName());
            out.put("serverVersion", p.serverVersion());
            out.put("tools", p.tools().stream().map(t -> {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", t.name());
                m.put("description", t.description());
                m.put("inputSchema", t.inputSchema());
                return m;
            }).collect(Collectors.toList()));
            log.info("Connector '{}' probed: {} tool(s) from {}", c.slug(), p.tools().size(), p.serverName());
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return out;
    }

    /** Call one of the connector's tools and return its text output. */
    public String callTool(String connectorId, String toolName, Map<String, Object> args)
            throws IOException {
        ConnectorSpec c = spec(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found: " + connectorId));
        if (!c.enabled()) {
            throw new IOException("Connector '" + c.name() + "' is disabled.");
        }
        if (!gate(c, "call " + toolName + " on")) {
            throw new IOException("Reaching " + c.endpoint() + " was not approved.");
        }
        return mcpClient.callTool(c, toolName, args);
    }

    /**
     * The network gate. Local transports pass straight through; a remote endpoint waits for the
     * user to approve this specific connection.
     */
    private boolean gate(ConnectorSpec c, String action) {
        if (!c.isRemote()) return true;
        log.warn("[Connector] '{}' wants to {} a remote host: {}", c.slug(), action, c.endpoint());
        return approvals.awaitApproval(
                "connector:" + c.slug(),
                "Connect to an external MCP server",
                "mcp",
                action + " " + c.endpoint(),
                c.endpoint());
    }

    // -- Deploy ----------------------------------------------------------------

    /**
     * Write the connector into the harness as an {@code .mcp.json} entry, so the agentic CLI at
     * {@code target} reaches the same server Arima does. Merges into any existing file rather than
     * replacing it — other servers already configured there are left alone.
     */
    @SuppressWarnings("unchecked")
    public Deployment deploy(String connectorId, String target) throws IOException {
        ConnectorSpec c = spec(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found: " + connectorId));
        String t = deployments.normalizeTarget(target);

        Path file = deployments.resolveWithin(t, ".mcp.json");
        Map<String, Object> root = new LinkedHashMap<>();
        if (java.nio.file.Files.exists(file)) {
            try {
                root = mapper.readValue(java.nio.file.Files.readString(file), LinkedHashMap.class);
            } catch (Exception e) {
                log.warn("Could not parse the existing {} — writing a fresh one: {}", file, e.getMessage());
                root = new LinkedHashMap<>();
            }
        }
        Map<String, Object> servers = root.get("mcpServers") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        servers.put(c.slug(), serverEntry(c));
        root.put("mcpServers", servers);

        Path written = deployments.write(t, ".mcp.json", mapper.writeValueAsString(root) + "\n");
        return deployments.record(c.id(), "connector", c.slug(), t, null, List.of(written));
    }

    /** The {@code .mcp.json} entry for this connector, in the shape the CLIs read. */
    private Map<String, Object> serverEntry(ConnectorSpec c) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (c.isStdio()) {
            List<String> argv = c.command();
            entry.put("command", argv.isEmpty() ? "" : argv.get(0));
            entry.put("args", argv.size() > 1 ? argv.subList(1, argv.size()) : List.of());
            if (c.env() != null && !c.env().isEmpty()) entry.put("env", c.env());
        } else {
            entry.put("type", "sse");
            entry.put("url", c.url());
        }
        return entry;
    }

    /**
     * Undeploy needs care here: the file is shared. Remove just this connector's entry, and only
     * delete the file when nothing else is left in it.
     */
    @SuppressWarnings("unchecked")
    public int undeploy(String connectorId, String target) throws IOException {
        ConnectorSpec c = spec(connectorId).orElse(null);
        String t = deployments.normalizeTarget(target);
        if (c == null) return deployments.undeploy(connectorId, t);

        Path file = deployments.resolveWithin(t, ".mcp.json");
        if (java.nio.file.Files.exists(file)) {
            Map<String, Object> root;
            try {
                root = mapper.readValue(java.nio.file.Files.readString(file), LinkedHashMap.class);
            } catch (Exception e) {
                root = new LinkedHashMap<>();
            }
            if (root.get("mcpServers") instanceof Map<?, ?> m) {
                Map<String, Object> servers = new LinkedHashMap<>((Map<String, Object>) m);
                servers.remove(c.slug());
                if (servers.isEmpty()) {
                    return deployments.undeploy(c.id(), t);   // nothing left: let the record delete it
                }
                root.put("mcpServers", servers);
                deployments.write(t, ".mcp.json", mapper.writeValueAsString(root) + "\n");
            }
        }
        deployments.forget(c.id(), t);
        return 1;
    }

    // -- internals -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    ConnectorSpec toSpec(Notebook nb) {
        Map<String, Object> meta = nb.getMetadata() == null ? Map.of() : nb.getMetadata();
        String transport = ConnectorSpec.STDIO;
        List<String> command = List.of();
        String url = null;
        Map<String, String> env = Map.of();
        boolean enabled = true;

        if (meta.get("connector") instanceof Map<?, ?> c) {
            transport = ConnectorSpec.transportOf(str(c.get("transport")));
            if (c.get("command") instanceof List<?> l) {
                command = l.stream().map(String::valueOf).filter(s -> !s.isBlank())
                        .collect(Collectors.toList());
            }
            if (c.get("url") instanceof String u && !u.isBlank()) url = u.trim();
            if (c.get("env") instanceof Map<?, ?> e) {
                Map<String, String> m = new LinkedHashMap<>();
                ((Map<String, Object>) e).forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
                env = m;
            }
            if (c.get("enabled") != null) enabled = Boolean.parseBoolean(String.valueOf(c.get("enabled")));
        }

        String notes = nb.getCells().stream()
                .filter(x -> x.getType() == CellType.MARKDOWN)
                .map(Cell::getSource)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"))
                .strip();

        return new ConnectorSpec(nb.getId(), nb.getName(), AgentSpec.slugify(nb.getName()),
                nb.getDescription(), transport, command, url, env, enabled, notes);
    }

    private boolean isConnector(Notebook nb) {
        return nb.getMetadata() != null && "connector".equals(str(nb.getMetadata().get("kind")));
    }

    private boolean isConnectorMeta(Map<String, Object> meta) {
        return meta.get("metadata") instanceof Map<?, ?> m && "connector".equals(str(m.get("kind")));
    }

    private Optional<Notebook> load(String id) {
        if (id == null) return Optional.empty();
        return notebookService.getNotebook(id, userService.getCurrentUser().getId())
                .or(() -> notebookService.getTutorial(id));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
