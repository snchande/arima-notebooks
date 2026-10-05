package com.barista.model;

import java.util.List;

/**
 * A provider-neutral description of an agent or a skill, derived from an "agent notebook".
 *
 * There is no new storage format — an agent notebook is a normal {@link Notebook} with a
 * {@code metadata.kind} flag; {@link com.barista.service.AgentService} projects it into this
 * shape. Each {@link com.barista.service.AgentProvider} then knows how to <em>run</em> it and
 * how to <em>export</em> it to that CLI's native files.
 *
 * @param name        agent/skill name (kebab-cased on export)
 * @param description one-line description (frontmatter)
 * @param kind        AGENT (has tools), SKILL (instructions only), TOOL (a callable body) or
 *                    PLUGIN (a bundle of the others)
 * @param tools       declared tool names — agents only; empty for skills
 * @param body        the instructions / system prompt (the notebook's markdown cells, concatenated)
 */
public record AgentSpec(String name, String description, Kind kind, List<String> tools, String body) {

    public enum Kind {
        AGENT, SKILL, TOOL, PLUGIN;

        /** The {@code metadata.kind} string for this kind. */
        public String key() { return name().toLowerCase(); }
    }

    /** Parse a {@code metadata.kind} string; anything unrecognized is an AGENT. */
    public static Kind kindOf(String s) {
        if (s == null) return Kind.AGENT;
        return switch (s.trim().toLowerCase()) {
            case "skill"  -> Kind.SKILL;
            case "tool"   -> Kind.TOOL;
            case "plugin" -> Kind.PLUGIN;
            default       -> Kind.AGENT;
        };
    }

    /** Kebab-case a display name for use as a filename, MCP tool name or frontmatter name. */
    public static String slugify(String name) {
        String s = (name == null ? "" : name).toLowerCase().trim()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isBlank() ? "unnamed" : s;
    }
}
