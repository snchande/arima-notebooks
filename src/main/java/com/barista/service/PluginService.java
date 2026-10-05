package com.barista.service;

import com.barista.model.AgentSpec;
import com.barista.model.Cell;
import com.barista.model.CellType;
import com.barista.model.Deployment;
import com.barista.model.Notebook;
import com.barista.model.PluginSpec;
import com.barista.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Bundle agents, skills and tools into one installable unit.
 *
 * A plugin notebook is a normal {@link Notebook} with {@code metadata.kind = "plugin"} and a
 * {@code metadata.plugin} block naming its members. Deploying one builds a Claude Code plugin
 * directory:
 *
 * <pre>
 * &lt;target&gt;/plugins/&lt;slug&gt;/
 *   .claude-plugin/plugin.json   name, version, description, author
 *   agents/&lt;slug&gt;.md             each member agent
 *   skills/&lt;slug&gt;/SKILL.md       each member skill
 *   commands/&lt;slug&gt;.md           one per member tool, routed over Arima's MCP server
 *   .mcp.json                    points the plugin back at Arima's MCP endpoint
 *   README.md                    the plugin notebook's markdown cells
 * </pre>
 *
 * Member tools are not re-implemented in the bundle — they stay in Arima and are reached over the
 * in-process MCP server, so there is one source of truth and no new outbound host.
 */
@Service
public class PluginService {

    private static final Logger log = LoggerFactory.getLogger(PluginService.class);

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final NotebookService notebookService;
    private final UserService userService;
    private final AgentService agentService;
    private final ToolService toolService;
    private final DeploymentService deployments;

    @Value("${server.port:8585}")
    private int serverPort;

    public PluginService(NotebookService notebookService,
                         UserService userService,
                         AgentService agentService,
                         ToolService toolService,
                         DeploymentService deployments) {
        this.notebookService = notebookService;
        this.userService = userService;
        this.agentService = agentService;
        this.toolService = toolService;
        this.deployments = deployments;
    }

    // -- Catalog ---------------------------------------------------------------

    /**
     * Every plugin definition available to the current user. Each entry is the card projection:
     * {@code id, name, slug, description, version, members, memberCount, source, deployedTo}.
     */
    public List<Map<String, Object>> list() {
        String userId = userService.getCurrentUser().getId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> meta : notebookService.listNotebooks(userId)) {
            if (isPluginMeta(meta)) spec(str(meta.get("id"))).ifPresent(p -> out.add(card(p, "mine")));
        }
        for (Map<String, Object> meta : notebookService.listTutorials()) {
            if (isPluginMeta(meta)) spec(str(meta.get("id"))).ifPresent(p -> out.add(card(p, "sample")));
        }
        return out;
    }

    /** The spec for one plugin, by notebook id. */
    public Optional<PluginSpec> spec(String pluginId) {
        return load(pluginId).filter(this::isPlugin).map(this::toSpec);
    }

    private Map<String, Object> card(PluginSpec p, String source) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", p.id());
        card.put("name", p.name());
        card.put("slug", p.slug());
        card.put("description", p.description());
        card.put("version", p.version());
        card.put("author", p.author());
        card.put("members", resolveMembers(p));
        card.put("memberCount", p.members().size());
        card.put("source", source);
        card.put("deployedTo", DeploymentService.TARGETS.stream()
                .filter(t -> deployments.isDeployed(p.id(), t))
                .collect(Collectors.toList()));
        return card;
    }

    /**
     * Describe each member so the UI can show what the bundle contains and flag members that no
     * longer resolve: {@code [{id, name, kind, missing}]}.
     */
    public List<Map<String, Object>> resolveMembers(PluginSpec p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String memberId : p.members()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", memberId);
            Optional<Notebook> nb = load(memberId);
            if (nb.isEmpty()) {
                m.put("name", memberId);
                m.put("kind", "missing");
                m.put("missing", true);
            } else {
                m.put("name", nb.get().getName());
                m.put("kind", kindOf(nb.get()).key());
                m.put("missing", false);
            }
            out.add(m);
        }
        return out;
    }

    // -- Authoring -------------------------------------------------------------

    /** Create a plugin notebook, pre-seeded with a README cell and an empty member list. */
    public Notebook create(String name) {
        String userId = userService.getCurrentUser().getId();
        String nbName = (name == null || name.isBlank()) ? "New Plugin" : name.trim();

        Notebook nb = notebookService.createNotebook(nbName, userId);
        nb.setDescription("What this plugin gives an agentic CLI, in one line.");
        nb.getMetadata().put("kind", "plugin");

        Map<String, Object> pluginMeta = new LinkedHashMap<>();
        pluginMeta.put("version", "0.1.0");
        pluginMeta.put("author", userService.getCurrentUser().getName());
        pluginMeta.put("members", new ArrayList<String>());
        nb.getMetadata().put("plugin", pluginMeta);

        Cell readme = new Cell();
        readme.setType(CellType.MARKDOWN);
        readme.setSource("# " + nbName + "\n\nWhat this plugin installs, and how to use it.\n\n"
                + "Add agents, skills and tools as **members** in the banner above, then deploy — Arima "
                + "builds a Claude Code plugin directory from them. These markdown cells become the "
                + "plugin's README.");

        nb.getCells().clear();
        nb.getCells().add(readme);
        return notebookService.saveNotebook(nb, userId);
    }

    // -- Deploy ----------------------------------------------------------------

    /**
     * Build and install the plugin into {@code target}, returning the deploy record. Members that
     * no longer resolve are skipped with a warning rather than failing the whole bundle.
     */
    public Deployment deploy(String pluginId, String target) throws IOException {
        PluginSpec plugin = spec(pluginId)
                .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + pluginId));
        String t = deployments.normalizeTarget(target);
        String base = "plugins/" + plugin.slug();
        List<Path> written = new ArrayList<>();

        // .claude-plugin/plugin.json — the manifest that makes this a plugin.
        written.add(deployments.write(t, base + "/.claude-plugin/plugin.json", manifest(plugin)));

        // README.md — the plugin notebook's own markdown.
        String readme = plugin.readme().isBlank()
                ? ("# " + plugin.name() + "\n\n" + nullToEmpty(plugin.description()) + "\n")
                : plugin.readme() + "\n";
        written.add(deployments.write(t, base + "/README.md", readme));

        List<ToolSpec> memberTools = new ArrayList<>();
        for (String memberId : plugin.members()) {
            Optional<Notebook> nb = load(memberId);
            if (nb.isEmpty()) {
                log.warn("Plugin '{}' lists member '{}', which no longer exists — skipping",
                        plugin.slug(), memberId);
                continue;
            }
            Notebook member = nb.get();
            switch (kindOf(member)) {
                case AGENT -> {
                    AgentSpec spec = agentService.toSpec(member);
                    String slug = AgentSpec.slugify(spec.name());
                    written.add(deployments.write(t, base + "/agents/" + slug + ".md",
                            markdownDefinition(spec, slug)));
                }
                case SKILL -> {
                    AgentSpec spec = agentService.toSpec(member);
                    String slug = AgentSpec.slugify(spec.name());
                    written.add(deployments.write(t, base + "/skills/" + slug + "/SKILL.md",
                            markdownDefinition(spec, slug)));
                }
                case TOOL -> {
                    ToolSpec tool = toolService.toSpec(member);
                    memberTools.add(tool);
                    written.add(deployments.write(t, base + "/commands/" + tool.slug() + ".md",
                            toolCommand(tool)));
                }
                case PLUGIN -> log.warn("Plugin '{}' lists plugin '{}' as a member — plugins do not "
                        + "nest; skipping", plugin.slug(), memberId);
            }
        }

        // .mcp.json — how the installed plugin reaches the tools, which stay in Arima.
        if (!memberTools.isEmpty()) {
            written.add(deployments.write(t, base + "/.mcp.json", mcpConfig()));
        }

        return deployments.record(plugin.id(), "plugin", plugin.slug(), t, null, written);
    }

    /** The plugin.json manifest. */
    private String manifest(PluginSpec plugin) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", plugin.slug());
        m.put("version", plugin.version());
        m.put("description", oneLine(plugin.description()));
        if (plugin.author() != null && !plugin.author().isBlank()) {
            m.put("author", Map.of("name", plugin.author()));
        }
        return mapper.writeValueAsString(m) + "\n";
    }

    /**
     * The MCP server entry an installed plugin uses to reach Arima's tools. Arima binds to
     * loopback only, so this address is reachable from this machine and nowhere else.
     */
    private String mcpConfig() throws IOException {
        Map<String, Object> arima = new LinkedHashMap<>();
        arima.put("type", "sse");
        arima.put("url", "http://127.0.0.1:" + serverPort + "/api/mcp/sse");
        return mapper.writeValueAsString(Map.of("mcpServers", Map.of("arima", arima))) + "\n";
    }

    /** Frontmatter + body, the shape every agentic CLI reads an agent or skill from. */
    private String markdownDefinition(AgentSpec spec, String slug) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("name: ").append(slug).append('\n');
        sb.append("description: ").append(oneLine(spec.description())).append('\n');
        if (spec.kind() == AgentSpec.Kind.AGENT && spec.tools() != null && !spec.tools().isEmpty()) {
            sb.append("tools: ").append(String.join(", ", spec.tools())).append('\n');
        }
        sb.append("---\n\n").append(spec.body().strip()).append('\n');
        return sb.toString();
    }

    /**
     * A slash command that calls the tool over MCP. The tool's implementation is not copied — the
     * command tells the CLI which Arima tool to invoke and with what.
     */
    private String toolCommand(ToolSpec tool) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("description: ").append(oneLine(tool.description())).append('\n');
        sb.append("---\n\n");
        sb.append("Invoke the Arima tool `").append(tool.slug()).append("` over the `arima` MCP ")
          .append("server (tool name `arima_").append(tool.slug().replace('-', '_')).append("`).\n\n");
        if (tool.params().isEmpty()) {
            sb.append("It takes no parameters.\n");
        } else {
            sb.append("Parameters:\n\n");
            for (ToolSpec.Param p : tool.params()) {
                sb.append("- `").append(p.name()).append("` (").append(p.type())
                  .append(p.required() ? ", required" : ", optional").append(") — ")
                  .append(oneLine(p.description())).append('\n');
            }
            sb.append("\nTake the values from the user's arguments: $ARGUMENTS\n");
        }
        sb.append("\nReport the tool's output verbatim.\n");
        return sb.toString();
    }

    // -- internals -------------------------------------------------------------

    PluginSpec toSpec(Notebook nb) {
        Map<String, Object> meta = nb.getMetadata() == null ? Map.of() : nb.getMetadata();
        String version = "0.1.0";
        String author = null;
        List<String> members = List.of();
        if (meta.get("plugin") instanceof Map<?, ?> p) {
            if (p.get("version") instanceof String v && !v.isBlank()) version = v.trim();
            if (p.get("author") instanceof String a && !a.isBlank()) author = a.trim();
            if (p.get("members") instanceof List<?> l) {
                members = l.stream().map(String::valueOf).filter(s -> !s.isBlank()).distinct()
                        .collect(Collectors.toList());
            }
        }
        String readme = nb.getCells().stream()
                .filter(c -> c.getType() == CellType.MARKDOWN)
                .map(Cell::getSource)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"))
                .strip();

        return new PluginSpec(nb.getId(), nb.getName(), AgentSpec.slugify(nb.getName()),
                nb.getDescription(), version, author, members, readme);
    }

    private AgentSpec.Kind kindOf(Notebook nb) {
        Map<String, Object> meta = nb.getMetadata() == null ? Map.of() : nb.getMetadata();
        return AgentSpec.kindOf(str(meta.get("kind")));
    }

    private boolean isPlugin(Notebook nb) {
        return nb.getMetadata() != null && "plugin".equals(str(nb.getMetadata().get("kind")));
    }

    private boolean isPluginMeta(Map<String, Object> meta) {
        return meta.get("metadata") instanceof Map<?, ?> m && "plugin".equals(str(m.get("kind")));
    }

    private Optional<Notebook> load(String id) {
        if (id == null) return Optional.empty();
        return notebookService.getNotebook(id, userService.getCurrentUser().getId())
                .or(() -> notebookService.getTutorial(id));
    }

    private String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
