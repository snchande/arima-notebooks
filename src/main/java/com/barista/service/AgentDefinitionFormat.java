package com.barista.service;

import com.barista.model.AgentSpec;

/**
 * The on-disk shape of an agent or skill definition: YAML frontmatter followed by the
 * instructions. Claude, Copilot and Antigravity all read this same format — only the directory
 * differs — so each {@link AgentProvider} shares one writer rather than three near-copies.
 */
final class AgentDefinitionFormat {

    private AgentDefinitionFormat() {}

    /** Frontmatter ({@code name}, {@code description}, and {@code tools} for agents) + body. */
    static String markdown(AgentSpec spec, String slug) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("name: ").append(slug).append('\n');
        sb.append("description: ").append(oneLine(spec.description())).append('\n');
        if (spec.kind() == AgentSpec.Kind.AGENT && spec.tools() != null && !spec.tools().isEmpty()) {
            sb.append("tools: ").append(String.join(", ", spec.tools())).append('\n');
        }
        sb.append("---\n\n").append(spec.body().strip()).append('\n');
        return sb.toString();
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
