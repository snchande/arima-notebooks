package com.barista.service;

import com.barista.model.AgentSpec;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The seam that makes "agents & skills for each agentic CLI" extensible: one implementation per
 * system (Claude, Copilot, Antigravity). Add a provider and it gets both verbs — run and export —
 * from {@link AgentService} for free.
 */
public interface AgentProvider {

    /** Stable key used by the UI / API to select this provider, e.g. "claude". */
    String key();

    /** Whether the underlying CLI is installed and usable right now. */
    boolean available();

    /**
     * Run the agent/skill against a task, streaming output chunks to {@code sink} as they arrive.
     * Returns the full response text. Throws with an actionable message if the CLI is missing.
     */
    String run(AgentSpec spec, String task, Consumer<String> sink) throws Exception;

    /**
     * Write the agent/skill to this provider's native files under {@code repoRoot}
     * (e.g. {@code .claude/agents/<name>.md}). Returns the primary file written.
     */
    Path export(AgentSpec spec, Path repoRoot) throws Exception;
}
