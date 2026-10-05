package com.barista.service;

import com.barista.model.AgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Runs and exports agents/skills through GitHub Copilot.
 *
 * Running reuses {@link GitHubCopilotService}, which drives the local {@code copilot} CLI through
 * the Copilot SDK in chat-only mode. That seam returns the whole response at once rather than a
 * stream, so {@link #run} hands the sink a single chunk — the UI renders it identically.
 *
 * Exporting writes Copilot's native layout: {@code .github/agents/<name>.md} for an agent and
 * {@code .github/skills/<name>/SKILL.md} for a skill.
 */
@Service
public class CopilotAgentProvider implements AgentProvider {

    private static final Logger log = LoggerFactory.getLogger(CopilotAgentProvider.class);

    private final GitHubCopilotService copilot;

    public CopilotAgentProvider(GitHubCopilotService copilot) {
        this.copilot = copilot;
    }

    @Override public String key() { return "copilot"; }

    @Override public boolean available() { return copilot.isAvailable(); }

    @Override
    public String run(AgentSpec spec, String task, Consumer<String> sink) throws Exception {
        if (!copilot.isAvailable()) {
            throw new IllegalStateException(
                "GitHub Copilot CLI not found. Install the `copilot` CLI and sign in, then retry.");
        }
        log.info("Agent run via Copilot: {} ({} chars body)", spec.name(), spec.body().length());

        String response = copilot.chat(task == null ? "" : task.strip(), systemPrompt(spec));
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Copilot returned an empty response.");
        }
        if (sink != null) sink.accept(response);
        return response.trim();
    }

    @Override
    public Path export(AgentSpec spec, Path repoRoot) throws Exception {
        String slug = AgentSpec.slugify(spec.name());
        Path file = spec.kind() == AgentSpec.Kind.SKILL
                ? repoRoot.resolve(".github").resolve("skills").resolve(slug).resolve("SKILL.md")
                : repoRoot.resolve(".github").resolve("agents").resolve(slug + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, AgentDefinitionFormat.markdown(spec, slug), StandardCharsets.UTF_8);
        log.info("Exported {} '{}' -> {}", spec.kind(), spec.name(), file);
        return file;
    }

    /** The agent's instructions, plus its tool grants, as a system prompt. */
    private String systemPrompt(AgentSpec spec) {
        StringBuilder sb = new StringBuilder(spec.body().strip());
        if (spec.kind() == AgentSpec.Kind.AGENT && spec.tools() != null && !spec.tools().isEmpty()) {
            sb.append("\n\nYou may use these tools: ").append(String.join(", ", spec.tools())).append('.');
        }
        return sb.toString();
    }
}
