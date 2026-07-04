package com.barista.service;

import com.github.copilot.CopilotClient;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.PermissionRequestResultKind;
import com.github.copilot.rpc.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * GitHub Copilot integration via the official GitHub Copilot SDK for Java
 * ({@code com.github:copilot-sdk-java}, MIT-licensed).
 *
 * The SDK drives the local GitHub Copilot CLI ({@code copilot}) in server mode over
 * JSON-RPC — replacing the previous raw-stdin piping with a typed session API
 * (assistant-message events, usage metrics, permission handling). Authentication is
 * reused from the {@code copilot} CLI login; Arima stores no API key and opens no new
 * outbound host of its own (the CLI engine handles model access, exactly as before).
 *
 * Permission model: every tool/permission request is REJECTED ({@link #DENY_ALL}), so the
 * assistant runs in chat-only mode. Any code it returns is applied to notebook cells through
 * the Arima UI (auto-apply + undo) — the SDK never edits files on disk on its own.
 *
 * Prerequisites:
 *   Install the GitHub Copilot CLI ({@code copilot}, v1.0.55-5+) on PATH and authenticate it.
 */
@Service
public class CopilotCliService {

    private static final Logger log = LoggerFactory.getLogger(CopilotCliService.class);

    /** Chat-only guard: reject every permission request so the agent cannot run tools or edit files. */
    private static final PermissionHandler DENY_ALL = (request, invocation) ->
        CompletableFuture.completedFuture(
            new PermissionRequestResult().setKind(PermissionRequestResultKind.REJECTED));

    private final SettingsService settingsService;

    public CopilotCliService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public String chat(String userMessage, String systemPrompt) throws IOException, InterruptedException {
        return chat(List.of(Map.of("role", "user", "content", userMessage)), systemPrompt);
    }

    public String chat(List<Map<String, String>> messages, String systemPrompt)
            throws IOException, InterruptedException {

        if (findCopilotExecutable() == null) {
            throw new IllegalStateException(
                "GitHub Copilot CLI not found.\n\n" +
                "The Copilot SDK drives the local `copilot` CLI. Install it (v1.0.55-5 or later),\n" +
                "add it to your PATH, and authenticate it, then try again.");
        }
        return chatViaSdk(buildPrompt(messages, systemPrompt));
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
        return findCopilotExecutable() != null;
    }

    public String getStatusDetail() {
        String exe = findCopilotExecutable();
        if (exe == null) return "copilot CLI not found — install the GitHub Copilot CLI (v1.0.55-5+) and add it to PATH";
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return out.isBlank() ? ("✓ Copilot SDK via " + exe) : ("✓ Copilot SDK · " + out.split("\\r?\\n")[0]);
        } catch (Exception e) {
            return "✓ Copilot SDK via " + exe;
        }
    }

    private String buildPrompt(List<Map<String, String>> messages, String systemPrompt) {
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
        return prompt.toString();
    }

    private String chatViaSdk(String prompt) throws IOException, InterruptedException {
        log.info("Copilot SDK chat: prompt length {}", prompt.length());

        final String[] holder = { null };
        CopilotClient client = null;
        try {
            client = new CopilotClient();
            client.start().get(60, TimeUnit.SECONDS);

            var session = client.createSession(
                new SessionConfig().setOnPermissionRequest(DENY_ALL)).get(60, TimeUnit.SECONDS);

            session.on(AssistantMessageEvent.class, msg -> {
                String c = msg.getData().content();
                if (c != null && !c.isBlank()) holder[0] = c;
            });

            session.sendAndWait(new MessageOptions().setPrompt(prompt)).get(180, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            log.warn("Copilot SDK error", e);
            throw new IOException("Copilot SDK error: " + e.getMessage()
                + "\n\nMake sure the `copilot` CLI (v1.0.55-5+) is installed and authenticated.", e);
        } finally {
            if (client != null) {
                try { client.close(); } catch (Exception ignore) {}
            }
        }

        if (holder[0] == null || holder[0].isBlank()) {
            throw new IOException("Copilot SDK returned no assistant message. "
                + "Make sure the `copilot` CLI is authenticated.");
        }
        return holder[0].trim();
    }

    private String findCopilotExecutable() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String home = System.getProperty("user.home", "");

        java.util.List<String> candidates = new java.util.ArrayList<>(java.util.List.of(
            home + "/AppData/Roaming/npm/copilot.cmd",
            home + "/AppData/Local/Programs/copilot/copilot.exe",
            home + "/.local/bin/copilot",
            "/usr/local/bin/copilot",
            "/usr/bin/copilot",
            "/opt/homebrew/bin/copilot"
        ));

        for (String path : candidates) {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.canRead()) {
                log.debug("Found copilot at: {}", path);
                return path;
            }
        }

        try {
            List<String> cmd = isWindows
                ? List.of("cmd", "/c", "where", "copilot")
                : List.of("which", "copilot");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !result.isBlank()) {
                String found = result.split("\\r?\\n")[0].trim();
                log.debug("Found copilot via PATH: {}", found);
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
