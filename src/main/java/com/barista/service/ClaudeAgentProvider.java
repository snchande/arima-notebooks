package com.barista.service;

import com.barista.model.AgentSpec;
import com.barista.util.BaristaHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Runs and exports agents/skills through the local {@code claude} CLI — the same subprocess model
 * {@link ClaudeService} already uses (working dir = repo root so {@code .claude/} is auto-loaded).
 */
@Service
public class ClaudeAgentProvider implements AgentProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAgentProvider.class);

    private final ClaudeService claudeService;

    public ClaudeAgentProvider(ClaudeService claudeService) {
        this.claudeService = claudeService;
    }

    @Override public String key() { return "claude"; }

    @Override public boolean available() { return claudeService.isAvailable(); }

    @Override
    public String run(AgentSpec spec, String task, Consumer<String> sink) throws Exception {
        String exe = claudeService.executable();
        if (exe == null) {
            throw new IllegalStateException(
                "Claude CLI not found. Install it (https://claude.ai/code) and run `claude auth`.");
        }

        String prompt = buildPrompt(spec, task);
        log.info("Agent run via claude CLI: {} ({} chars body)", spec.name(), spec.body().length());

        ProcessBuilder pb = new ProcessBuilder(exe, "--print");
        pb.directory(BaristaHome.directory());
        pb.redirectErrorStream(false);
        Process p = pb.start();

        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder full = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) != -1) {
                String chunk = new String(buf, 0, n);
                full.append(chunk);
                if (sink != null) sink.accept(chunk);   // stream as it arrives
            }
        }

        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0 || full.toString().isBlank()) {
            String err = stderr.isBlank() ? ("exit code " + code) : stderr;
            throw new IOException("Claude CLI error: " + err + "\n\nRun `claude auth` to sign in.");
        }
        return full.toString().trim();
    }

    @Override
    public Path export(AgentSpec spec, Path repoRoot) throws Exception {
        String slug = slugify(spec.name());
        String content = frontmatter(spec, slug) + "\n" + spec.body().strip() + "\n";

        Path file;
        if (spec.kind() == AgentSpec.Kind.SKILL) {
            Path dir = repoRoot.resolve(".claude").resolve("skills").resolve(slug);
            Files.createDirectories(dir);
            file = dir.resolve("SKILL.md");
        } else {
            Path dir = repoRoot.resolve(".claude").resolve("agents");
            Files.createDirectories(dir);
            file = dir.resolve(slug + ".md");
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        log.info("Exported {} '{}' -> {}", spec.kind(), spec.name(), file);
        return file;
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private String buildPrompt(AgentSpec spec, String task) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.body().strip());
        if (spec.kind() == AgentSpec.Kind.AGENT && spec.tools() != null && !spec.tools().isEmpty()) {
            sb.append("\n\nYou may use these tools: ").append(String.join(", ", spec.tools())).append('.');
        }
        sb.append("\n\n---\n\n**Task:** ").append(task == null ? "" : task.strip()).append("\n");
        return sb.toString();
    }

    private String frontmatter(AgentSpec spec, String slug) {
        StringBuilder fm = new StringBuilder("---\n");
        fm.append("name: ").append(slug).append('\n');
        fm.append("description: ").append(oneLine(spec.description())).append('\n');
        if (spec.kind() == AgentSpec.Kind.AGENT && spec.tools() != null && !spec.tools().isEmpty()) {
            fm.append("tools: ").append(String.join(", ", spec.tools())).append('\n');
        }
        fm.append("---\n");
        return fm.toString();
    }

    private String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String slugify(String name) {
        String s = (name == null ? "agent" : name).toLowerCase().trim()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isBlank() ? "agent" : s;
    }
}
