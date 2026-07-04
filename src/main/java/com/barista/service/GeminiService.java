package com.barista.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Antigravity AI integration via the local {@code agy} CLI (Google Antigravity).
 *
 * Google retired the standalone Gemini CLI on 2026-06-18 and replaced it with the
 * Antigravity CLI ({@code agy}). This service drives {@code agy} in its non-interactive
 * print mode ({@code agy -p "<prompt>"}) — no API key is stored in Arima as long as the
 * CLI is signed in (or {@code GEMINI_API_KEY} / {@code ANTIGRAVITY_API_KEY} is set in the
 * environment).
 *
 * The process runs with the Arima repo as its working directory, so Antigravity is
 * grounded in the full codebase (it reads AGENTS.md and project files on demand).
 *
 * Prerequisites:
 *   1. Install the Antigravity CLI (https://antigravity.google/docs/cli-install)
 *   2. Sign in: run {@code agy} once in a terminal, or set GEMINI_API_KEY / ANTIGRAVITY_API_KEY
 *
 * NOTE: the internal provider key remains {@code gemini_cli} for backward compatibility
 * with saved settings and the frontend provider switcher.
 */
@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final SettingsService settingsService;

    public GeminiService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public String chat(String userMessage, String systemPrompt) throws IOException, InterruptedException {
        return chat(List.of(Map.of("role", "user", "content", userMessage)), systemPrompt);
    }

    public String chat(List<Map<String, String>> messages, String systemPrompt)
            throws IOException, InterruptedException {

        String agyExe = findAgyExecutable();
        if (agyExe == null) {
            throw new IllegalStateException(
                "Antigravity CLI not found.\n\n" +
                "Arima requires the Google Antigravity CLI (agy) to be installed and signed in:\n" +
                "  1. Install: https://antigravity.google/docs/cli-install\n" +
                "  2. Sign in: run  agy  once in a terminal (or set GEMINI_API_KEY / ANTIGRAVITY_API_KEY)\n\n" +
                "The CLI is checked in these locations:\n" +
                "  ~/AppData/Local/agy/bin/agy.exe, ~/.local/bin/agy, and your system PATH.");
        }

        return chatViaCli(messages, systemPrompt, agyExe);
    }

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

    public String explainCode(String code) throws IOException, InterruptedException {
        return chat("Explain this Java code clearly and concisely:\n\n```java\n" + code + "\n```", null);
    }

    public String fixError(String code, String error) throws IOException, InterruptedException {
        return chat(String.format("""
            This Java code has an error. Please provide a corrected version and explain what was wrong.

            Code:
            ```java
            %s
            ```

            Error:
            ```
            %s
            ```
            """, code, error), null);
    }

    public boolean isAvailable() {
        return findAgyExecutable() != null;
    }

    public String getStatusDetail() {
        String exe = findAgyExecutable();
        if (exe == null) {
            return "Antigravity CLI (agy) not found — install from https://antigravity.google/docs/cli-install";
        }
        return "✓ Antigravity CLI: " + exe;
    }

    private String chatViaCli(List<Map<String, String>> messages, String systemPrompt, String agyExe)
            throws IOException, InterruptedException {

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

        log.info("Antigravity CLI chat via {}: {} messages", agyExe, messages.size());

        // `agy -p` runs a single prompt non-interactively and prints the response.
        // The repo working dir grounds Antigravity in the full Arima codebase.
        ProcessBuilder pb = new ProcessBuilder(agyExe, "-p", prompt.toString());
        pb.directory(com.barista.util.BaristaHome.directory());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Close stdin immediately (prompt passed as argument)
        process.getOutputStream().close();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode  = process.waitFor();

        if (exitCode != 0 || output.isBlank()) {
            String err = stderr.isBlank() ? ("exit code " + exitCode) : stderr;
            log.warn("Antigravity CLI failed ({}): {}", agyExe, err);
            throw new IOException("Antigravity CLI error: " + err
                + "\n\nRun `agy` in a terminal to sign in (or set GEMINI_API_KEY / ANTIGRAVITY_API_KEY).");
        }

        return output;
    }

    private String findAgyExecutable() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String home = System.getProperty("user.home", "");

        java.util.List<String> candidates = new java.util.ArrayList<>(java.util.List.of(
            home + "/AppData/Local/agy/bin/agy.exe",
            home + "/.local/bin/agy",
            "/usr/local/bin/agy",
            "/usr/bin/agy",
            "/opt/homebrew/bin/agy"
        ));

        for (String path : candidates) {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.canRead()) {
                log.debug("Found agy at: {}", path);
                return path;
            }
        }

        try {
            List<String> cmd = isWindows
                ? List.of("cmd", "/c", "where", "agy")
                : List.of("which", "agy");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !result.isBlank()) {
                String found = result.split("\\r?\\n")[0].trim();
                log.debug("Found agy via PATH: {}", found);
                return found;
            }
        } catch (Exception ignore) {}

        return null;
    }

    private String getDefaultSystemPrompt() {
        return """
            You are the AI assistant for Arima Notebooks, an interactive notebook environment.
            You help users write, debug, and understand code in Java, JavaScript, C++, C#, and F#.

            Key capabilities:
            - Java 21 with JShell (no class/method wrappers needed for simple code)
            - JavaScript/Node.js
            - C++17 with MSVC
            - C# and F# with .NET

            Be concise, helpful, and provide working code examples.
            Format code blocks with appropriate language markers (```java```, ```cpp```, etc.).
            """;
    }
}
