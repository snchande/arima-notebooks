package com.barista.service;

import com.barista.model.AgentSpec;
import com.barista.model.Cell;
import com.barista.model.CellType;
import com.barista.model.Notebook;
import com.barista.util.BaristaHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Author / run / export agents and skills. An "agent notebook" is a normal {@link Notebook} with
 * {@code metadata.kind = "agent" | "skill"}; this service projects it into an {@link AgentSpec} and
 * dispatches to the selected {@link AgentProvider}. Reuses the STOMP {@code partial_output} channel
 * for live run output — no new endpoints or WebSocket topics.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** Synthetic cell id the UI filters on for a streamed agent run. */
    public static final String RUN_CELL_ID = "__agent_run__";

    private final NotebookService notebookService;
    private final UserService userService;
    private final SimpMessagingTemplate messaging;
    private final Map<String, AgentProvider> providers;

    public AgentService(NotebookService notebookService,
                        UserService userService,
                        SimpMessagingTemplate messaging,
                        List<AgentProvider> providerBeans) {
        this.notebookService = notebookService;
        this.userService = userService;
        this.messaging = messaging;
        this.providers = providerBeans.stream()
                .collect(Collectors.toMap(AgentProvider::key, p -> p, (a, b) -> a, LinkedHashMap::new));
    }

    /** Availability of every registered provider, e.g. {"claude": true}. */
    public Map<String, Boolean> providerStatus() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        providers.forEach((k, p) -> out.put(k, p.available()));
        return out;
    }

    /** Create a new agent/skill notebook, pre-seeded with a starter instructions cell. */
    public Notebook create(String name, String kind) {
        String userId = userService.getCurrentUser().getId();
        String nbName = (name == null || name.isBlank())
                ? (AgentSpec.kindOf(kind) == AgentSpec.Kind.SKILL ? "New Skill" : "New Agent")
                : name.trim();
        Notebook nb = notebookService.createNotebook(nbName, userId);
        nb.getMetadata().put("kind", AgentSpec.kindOf(kind) == AgentSpec.Kind.SKILL ? "skill" : "agent");
        nb.setDescription("Describe what this " + kind + " does in one line.");

        Cell instructions = new Cell();
        instructions.setType(CellType.MARKDOWN);
        instructions.setSource("# Instructions\n\nYou are ... . When given a task, ... .\n\n"
                + "Write the system prompt for this " + kind + " here as ordinary markdown.");
        nb.getCells().clear();
        nb.getCells().add(instructions);

        return notebookService.saveNotebook(nb, userId);
    }

    /** Run the agent/skill in {@code notebookId} against {@code task}, streaming output to the browser. */
    public String run(String notebookId, String task, String providerKey, String sessionId) throws Exception {
        Notebook nb = loadOrThrow(notebookId);
        AgentSpec spec = toSpec(nb);
        AgentProvider provider = resolve(providerKey);

        final String topic = "/topic/shell/" + (sessionId == null ? notebookId : sessionId);
        Consumer<String> sink = chunk -> messaging.convertAndSend(topic, Map.of(
                "type", "partial_output", "cellId", RUN_CELL_ID, "text", chunk));

        return provider.run(spec, task, sink);
    }

    /** Export the agent/skill to the provider's native files; returns the written path. */
    public String export(String notebookId, String providerKey) throws Exception {
        Notebook nb = loadOrThrow(notebookId);
        AgentSpec spec = toSpec(nb);
        AgentProvider provider = resolve(providerKey);
        Path repoRoot = BaristaHome.directory().toPath();
        Path written = provider.export(spec, repoRoot);
        // Return a repo-relative path when possible (nicer for the UI toast).
        try { return repoRoot.relativize(written).toString().replace('\\', '/'); }
        catch (Exception ignore) { return written.toString(); }
    }

    // ── internals ──────────────────────────────────────────────────────────────
    AgentSpec toSpec(Notebook nb) {
        String body = nb.getCells().stream()
                .filter(c -> c.getType() == CellType.MARKDOWN)
                .map(Cell::getSource)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"))
                .strip();

        Map<String, Object> meta = nb.getMetadata() == null ? Map.of() : nb.getMetadata();
        AgentSpec.Kind kind = AgentSpec.kindOf(str(meta.get("kind")));
        List<String> tools = extractTools(meta);
        return new AgentSpec(nb.getName(), nb.getDescription(), kind, tools, body);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTools(Map<String, Object> meta) {
        Object agent = meta.get("agent");
        if (agent instanceof Map<?, ?> m && m.get("tools") instanceof List<?> l) {
            return l.stream().map(String::valueOf).filter(s -> !s.isBlank()).collect(Collectors.toList());
        }
        return List.of();
    }

    private AgentProvider resolve(String providerKey) {
        String key = (providerKey == null || providerKey.isBlank()) ? "claude" : providerKey;
        AgentProvider p = providers.get(key);
        if (p == null) throw new IllegalArgumentException("Unknown provider: " + key);
        if (!p.available()) {
            throw new IllegalStateException("Provider '" + key + "' is not available — install and sign in to its CLI.");
        }
        return p;
    }

    private Notebook loadOrThrow(String notebookId) {
        Optional<Notebook> nb = notebookService.getNotebook(notebookId, userService.getCurrentUser().getId())
                .or(() -> notebookService.getTutorial(notebookId));
        return nb.orElseThrow(() -> new IllegalArgumentException("Notebook not found: " + notebookId));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
