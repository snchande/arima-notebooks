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
 * Runs and exports agents/skills through the Antigravity CLI ({@code agy}).
 *
 * Keyed {@code "gemini"} for backward compatibility: that is the provider key the settings, the
 * status endpoint and the UI dropdowns already use, and it now routes to Antigravity — Google
 * retired the standalone Gemini CLI on 2026-06-18. Running reuses {@link GeminiService}, whose
 * chat seam returns the full response, so {@link #run} hands the sink one chunk.
 *
 * Exporting writes {@code .antigravity/agents/<name>.md} or
 * {@code .antigravity/skills/<name>/SKILL.md}.
 */
@Service
public class AntigravityAgentProvider implements AgentProvider {

    private static final Logger log = LoggerFactory.getLogger(AntigravityAgentProvider.class);

    private final GeminiService antigravity;

    public AntigravityAgentProvider(GeminiService antigravity) {
        this.antigravity = antigravity;
    }

    @Override public String key() { return "gemini"; }

    @Override public boolean available() { return antigravity.isAvailable(); }

    @Override
    public String run(AgentSpec spec, String task, Consumer<String> sink) throws Exception {
        if (!antigravity.isAvailable()) {
            throw new IllegalStateException("Antigravity CLI not found. Install `agy` "
                    + "(https://antigravity.google/docs/cli-install) and run it once to sign in.");
        }
        log.info("Agent run via Antigravity: {} ({} chars body)", spec.name(), spec.body().length());

        String response = antigravity.chat(task == null ? "" : task.strip(), systemPrompt(spec));
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Antigravity returned an empty response.");
        }
        if (sink != null) sink.accept(response);
        return response.trim();
    }

    @Override
    public Path export(AgentSpec spec, Path repoRoot) throws Exception {
        String slug = AgentSpec.slugify(spec.name());
        Path file = spec.kind() == AgentSpec.Kind.SKILL
                ? repoRoot.resolve(".antigravity").resolve("skills").resolve(slug).resolve("SKILL.md")
                : repoRoot.resolve(".antigravity").resolve("agents").resolve(slug + ".md");
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
