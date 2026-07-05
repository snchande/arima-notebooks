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
 * @param kind        AGENT (has tools) or SKILL (instructions only)
 * @param tools       declared tool names — agents only; empty for skills
 * @param body        the instructions / system prompt (the notebook's markdown cells, concatenated)
 */
public record AgentSpec(String name, String description, Kind kind, List<String> tools, String body) {

    public enum Kind { AGENT, SKILL }

    public static Kind kindOf(String s) {
        return (s != null && s.equalsIgnoreCase("skill")) ? Kind.SKILL : Kind.AGENT;
    }
}
