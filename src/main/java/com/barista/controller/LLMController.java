package com.barista.controller;

import com.barista.service.ClaudeService;
import com.barista.service.CopilotCliService;
import com.barista.service.GeminiService;
import com.barista.service.GitHubCopilotService;
import com.barista.service.PolyglotService;
import com.barista.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for AI integration.
 *
 * Supports three AI providers, configured in Settings:
 *   "claude_cli"      — Routes to ClaudeService (local Claude CLI / Claude Code Pro)
 *   "copilot_cli"     — Routes to CopilotCliService (local copilot CLI)
 *   "gemini_cli"      — Routes to GeminiService (Antigravity CLI `agy`; key retained for compatibility)
 *
 * POST /api/llm/chat           - Send a message and get a response
 * POST /api/llm/generate       - Generate a notebook from a prompt
 * POST /api/llm/explain        - Explain code
 * POST /api/llm/fix            - Suggest fix for an error
 * POST /api/llm/translate      - Render a cell's source in another language
 * GET  /api/llm/languages      - List the languages a cell can be compared against
 * GET  /api/llm/provider       - Report which AI provider is active + availability
 */
@RestController
@RequestMapping("/api/llm")
public class LLMController {

    private final ClaudeService          claudeService;
    private final CopilotCliService      copilotCliService;
    private final GeminiService          geminiService;
    private final SettingsService        settingsService;
    private final PolyglotService        polyglotService;

    public LLMController(ClaudeService claudeService,
                         CopilotCliService copilotCliService,
                         GeminiService geminiService,
                         SettingsService settingsService,
                         PolyglotService polyglotService) {
        this.claudeService     = claudeService;
        this.copilotCliService = copilotCliService;
        this.geminiService     = geminiService;
        this.settingsService   = settingsService;
        this.polyglotService   = polyglotService;
    }

    /** Report which AI provider is currently active and whether it is available. */
    @GetMapping("/provider")
    public ResponseEntity<Map<String, Object>> getProvider() {
        String provider = currentProvider();
        boolean available = switch (provider) {
            case "copilot_cli" -> copilotCliService.isAvailable();
            case "gemini_cli"  -> geminiService.isAvailable();
            default            -> claudeService.isAvailable();
        };
        String model = switch (provider) {
            case "copilot_cli" -> "copilot";
            case "gemini_cli"  -> settingsService.getSettings().getGeminiModel();
            default            -> settingsService.getSettings().getClaudeModel();
        };
        return ResponseEntity.ok(Map.of(
            "provider",  provider,
            "available", available,
            "model",     model != null ? model : ""
        ));
    }

    /** General chat endpoint */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> body) {
        try {
            String message     = (String) body.get("message");
            String systemPrompt = (String) body.get("systemPrompt");

            @SuppressWarnings("unchecked")
            List<Map<String, String>> history =
                    (List<Map<String, String>>) body.get("history");

            String response;
            if (history != null && !history.isEmpty()) {
                response = ai().chat(history, systemPrompt);
            } else {
                response = ai().chat(message, systemPrompt);
            }

            return ResponseEntity.ok(Map.of("response", response, "role", "assistant"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "AI error: " + e.getMessage()));
        }
    }

    /** Generate a complete notebook from a natural language prompt */
    @PostMapping("/generate")
    public ResponseEntity<?> generateNotebook(@RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'prompt' field"));
        }
        try {
            String notebookJson = ai().generateNotebook(prompt);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(notebookJson);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to generate notebook: " + e.getMessage()));
        }
    }

    /** Explain code */
    @PostMapping("/explain")
    public ResponseEntity<?> explainCode(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'code' field"));
        }
        try {
            return ResponseEntity.ok(Map.of("explanation", ai().explainCode(code)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Suggest a fix for code with an error */
    @PostMapping("/fix")
    public ResponseEntity<?> fixError(@RequestBody Map<String, String> body) {
        String code  = body.get("code");
        String error = body.get("error");
        if (code == null || error == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Both 'code' and 'error' fields are required"));
        }
        try {
            return ResponseEntity.ok(Map.of("fix", ai().fixError(code, error)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Render a cell's source in another language for the polyglot comparison view. */
    @PostMapping("/translate")
    public ResponseEntity<?> translate(@RequestBody Map<String, String> body) {
        String source = body.get("source");
        String from   = body.get("from");
        String to     = body.get("to");
        if (source == null || from == null || to == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Fields 'source', 'from' and 'to' are required"));
        }
        try {
            return ResponseEntity.ok(polyglotService.translate(source, from, to));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** The languages a cell can be compared against. */
    @GetMapping("/languages")
    public ResponseEntity<?> languages() {
        return ResponseEntity.ok(Map.of("languages", polyglotService.supportedLanguages()));
    }

    // ── Private helpers ─────────────────────────────────────────────────

    /** A minimal common interface implemented by both service wrappers. */
    private interface AIDelegate {
        String chat(String msg, String system) throws Exception;
        String chat(List<Map<String, String>> history, String system) throws Exception;
        String generateNotebook(String prompt) throws Exception;
        String explainCode(String code) throws Exception;
        String fixError(String code, String error) throws Exception;
    }

    /** Return the correct service as an AIDelegate based on settings. */
    private AIDelegate ai() {
        return switch (currentProvider()) {
            case "copilot_cli" -> new AIDelegate() {
                public String chat(String m, String s)                   throws Exception { return copilotCliService.chat(m, s); }
                public String chat(List<Map<String,String>> h, String s) throws Exception { return copilotCliService.chat(h, s); }
                public String generateNotebook(String p)                 throws Exception { return copilotCliService.generateNotebook(p); }
                public String explainCode(String c)                      throws Exception { return copilotCliService.explainCode(c); }
                public String fixError(String c, String e)               throws Exception { return copilotCliService.fixError(c, e); }
            };
            case "gemini_cli" -> new AIDelegate() {
                public String chat(String m, String s)                   throws Exception { return geminiService.chat(m, s); }
                public String chat(List<Map<String,String>> h, String s) throws Exception { return geminiService.chat(h, s); }
                public String generateNotebook(String p)                 throws Exception { return geminiService.generateNotebook(p); }
                public String explainCode(String c)                      throws Exception { return geminiService.explainCode(c); }
                public String fixError(String c, String e)               throws Exception { return geminiService.fixError(c, e); }
            };
            default -> new AIDelegate() {
                public String chat(String m, String s)                   throws Exception { return claudeService.chat(m, s); }
                public String chat(List<Map<String,String>> h, String s) throws Exception { return claudeService.chat(h, s); }
                public String generateNotebook(String p)                 throws Exception { return claudeService.generateNotebook(p); }
                public String explainCode(String c)                      throws Exception { return claudeService.explainCode(c); }
                public String fixError(String c, String e)               throws Exception { return claudeService.fixError(c, e); }
            };
        };
    }

    private String currentProvider() {
        String p = settingsService.getSettings().getAiProvider();
        return p != null ? p : "claude_cli";
    }
}
