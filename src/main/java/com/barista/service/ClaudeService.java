package com.barista.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Claude AI integration via the local {@code claude} CLI (Claude Code / Pro plan).
 *
 * All requests are routed through the CLI executable — no direct Anthropic API calls are made.
 * This avoids API rate limits, content-filter policy blocks, and API credit consumption.
 *
 * Provides:
 * - General chat/Q&A
 * - Java code generation
 * - Notebook generation from natural language prompts
 * - Code explanation and error analysis
 */
@Service
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);

    private final SettingsService settingsService;

    public ClaudeService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Send a message to Claude and get a text response.
     *
     * @param userMessage  The user's message
     * @param systemPrompt Optional system prompt (null for default)
     * @return Claude's response text
     */
    public String chat(String userMessage, String systemPrompt) throws IOException, InterruptedException {
        return chat(List.of(Map.of("role", "user", "content", userMessage)), systemPrompt);
    }

    /**
     * Send a conversation history to Claude and get a response.
     *
     * All requests go exclusively through the local {@code claude} CLI (Claude Code / Pro plan).
     * Direct Anthropic API calls are not used — this avoids API rate limits, content-filter
     * policy blocks, and avoids consuming API credits.
     *
     * @param messages     List of message maps with "role" and "content"
     * @param systemPrompt Optional system prompt
     * @return Claude's response text
     * @throws IllegalStateException if the claude CLI is not installed / authenticated
     */
    public String chat(List<Map<String, String>> messages, String systemPrompt)
            throws IOException, InterruptedException {

        String claudeExe = findClaudeExecutable();
        if (claudeExe == null) {
            throw new IllegalStateException(
                "Claude CLI not found.\n\n" +
                "Arima Notebooks requires the Claude Code CLI to be installed and signed in:\n" +
                "  1. Install: https://claude.ai/code\n" +
                "  2. Authenticate: run  claude auth  in a terminal\n\n" +
                "The CLI is checked in these locations:\n" +
                "  ~/.local/bin/claude, ~/AppData/Local/Programs/claude/claude.exe,\n" +
                "  ~/AppData/Roaming/npm/claude.cmd, and your system PATH.");
        }

        return chatViaCli(messages, systemPrompt, claudeExe);
    }

    /**
     * Generate a notebook from a natural language prompt.
     * Returns a JSON string matching the Notebook model format.
     */
    public String generateNotebook(String prompt) throws IOException, InterruptedException {
        String systemPrompt = """
            You are Arima Notebooks AI assistant. Generate a Arima notebook in JSON format.

            A Arima notebook is a JSON object with this structure:
            {
              "id": "generate-a-uuid",
              "name": "Notebook Title",
              "description": "Brief description",
              "cells": [
                {
                  "id": "cell-1",
                  "type": "MARKDOWN",
                  "source": "# Title\\n\\nMarkdown content here",
                  "output": "",
                  "executed": false
                },
                {
                  "id": "cell-2",
                  "type": "CODE",
                  "source": "// Java code here\\nSystem.out.println(\\"Hello\\");",
                  "output": "",
                  "executed": false
                }
              ],
              "metadata": {}
            }

            Rules:
            - Use Java 21 syntax
            - Include markdown cells for explanations
            - Code cells should be self-contained and runnable
            - Respond ONLY with valid JSON, no other text
            - Generate a proper UUID for the id field
            - Generate unique UUIDs for each cell id
            """;

        return chat("Create a Arima notebook for: " + prompt, systemPrompt);
    }

    /**
     * Explain Java code in plain English.
     */
    public String explainCode(String code) throws IOException, InterruptedException {
        String prompt = "Explain this Java code clearly and concisely:\n\n```java\n" + code + "\n```";
        return chat(prompt, null);
    }

    /**
     * Suggest a fix for a Java error.
     */
    public String fixError(String code, String error) throws IOException, InterruptedException {
        String prompt = String.format("""
            This Java code has an error. Please provide a corrected version and explain what was wrong.

            Code:
            ```java
            %s
            ```

            Error:
            ```
            %s
            ```
            """, code, error);
        return chat(prompt, null);
    }

    /**
     * Chat via the local `claude` CLI (Claude Code Pro plan).
     * Pipes the full conversation as a prompt to stdin so no shell-quoting or
     * argument-length limits apply.  Verified working: echo "..." | claude --print
     */
    private String chatViaCli(List<Map<String, String>> messages, String systemPrompt, String claudeExe)
            throws IOException, InterruptedException {

        // Build a single-turn prompt from the full history
        String sys = (systemPrompt != null && !systemPrompt.isBlank()) ? systemPrompt : getDefaultSystemPrompt();
        StringBuilder prompt = new StringBuilder();
        prompt.append(sys).append("\n\n---\n\n");
        for (Map<String, String> msg : messages) {
            String role    = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role))           prompt.append("**User:** ").append(content).append("\n\n");
            else if ("assistant".equals(role)) prompt.append("**Assistant:** ").append(content).append("\n\n");
        }
        prompt.append("**Assistant:**");

        log.info("Claude CLI chat via {}: {} messages", claudeExe, messages.size());

        // Pipe prompt via stdin; --print enables non-interactive single-turn mode.
        // Run from the Arima repo root so claude loads CLAUDE.md + AGENTS.md +
        // .claude skills/agents (no-op when BARISTA_HOME is unset — see BaristaHome).
        ProcessBuilder pb = new ProcessBuilder(claudeExe, "--print");
        pb.directory(com.barista.util.BaristaHome.directory());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Write to stdin and close (signals EOF so claude starts processing)
        try (java.io.OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.toString().getBytes(StandardCharsets.UTF_8));
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode  = process.waitFor();

        if (exitCode != 0 || output.isBlank()) {
            String err = stderr.isBlank() ? ("exit code " + exitCode) : stderr;
            log.warn("claude CLI failed ({}): {}", claudeExe, err);
            throw new IOException("Claude CLI error: " + err
                + "\n\nRun `claude auth` in a terminal to sign in.");
        }

        return output;
    }

    /**
     * Check whether the Claude CLI is installed and reachable.
     */
    public boolean isAvailable() {
        return findClaudeExecutable() != null;
    }

    /** Resolve the local {@code claude} CLI path (or null). Shared with the agent-run path. */
    public String executable() {
        return findClaudeExecutable();
    }

    /**
     * Locate the claude CLI executable.
     * Checks common install locations first, then falls back to PATH lookup.
     */
    private String findClaudeExecutable() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String home = System.getProperty("user.home", "");

        // Well-known install locations (ordered by likelihood)
        java.util.List<String> candidates = new java.util.ArrayList<>(java.util.List.of(
            home + "/.local/bin/claude",
            home + "/.local/bin/claude.exe",
            home + "/AppData/Local/Programs/claude/claude.exe",
            home + "/AppData/Roaming/npm/claude.cmd",
            "/usr/local/bin/claude",
            "/usr/bin/claude",
            "/opt/homebrew/bin/claude"
        ));

        for (String path : candidates) {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.canRead()) {
                log.debug("Found claude at: {}", path);
                return path;
            }
        }

        // Fall back to PATH lookup via `where` (Windows) / `which` (Unix)
        try {
            List<String> cmd = isWindows
                ? List.of("cmd", "/c", "where", "claude")
                : List.of("which", "claude");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !result.isBlank()) {
                String found = result.split("\\r?\\n")[0].trim();
                log.debug("Found claude via PATH: {}", found);
                return found;
            }
        } catch (Exception ignore) {}

        return null;
    }

    private String getDefaultSystemPrompt() {
        return """
            You are the AI assistant for Arima Notebooks, an interactive Java notebook environment.
            You help users write, debug, and understand Java code.

            Key capabilities:
            - Java 21 syntax and features
            - JShell-specific code (no need for class/method wrappers for simple code)
            - Maven package ecosystem
            - Java streams, generics, lambdas, records, and modern Java features

            Be concise, helpful, and provide working Java code examples when relevant.
            Format code blocks with ```java``` markers.
            """;
    }

}
