package com.barista.service;

import com.barista.model.CellTranslation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Renders one cell's source into another language, for the side-by-side polyglot view.
 *
 * The product goal is not transpilation. It is to let someone fluent in one language
 * read a second one in terms of the first, so the generated code is asked to stay
 * idiomatic in the target language, to comment wherever that language forces a
 * different approach, and to remain runnable - being able to execute the comparison
 * is what separates this from a table in a blog post.
 */
@Service
public class PolyglotService {

    private static final Logger log = LoggerFactory.getLogger(PolyglotService.class);

    /** Cell modes that can take part in a comparison, mapped to a human name. */
    public static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("jshell",     "Java (JShell REPL, top-level statements, no class wrapper)"),
            Map.entry("java",       "Java (a complete class with a main method)"),
            Map.entry("python",     "Python 3"),
            Map.entry("nodejs",     "JavaScript (Node.js)"),
            Map.entry("typescript", "TypeScript (Node.js)"),
            Map.entry("csharp",     "C# (.NET)"),
            Map.entry("fsharp",     "F# (.NET)"),
            Map.entry("cpp",        "C++17")
    );

    private final ClaudeService     claudeService;
    private final CopilotCliService copilotCliService;
    private final GeminiService     geminiService;
    private final SettingsService   settingsService;

    public PolyglotService(ClaudeService claudeService,
                           CopilotCliService copilotCliService,
                           GeminiService geminiService,
                           SettingsService settingsService) {
        this.claudeService     = claudeService;
        this.copilotCliService = copilotCliService;
        this.geminiService     = geminiService;
        this.settingsService   = settingsService;
    }

    public boolean isSupported(String mode) {
        return mode != null && LANGUAGES.containsKey(mode);
    }

    /**
     * Translate {@code source} from {@code fromMode} into {@code toMode}.
     *
     * @throws IllegalArgumentException if either mode is not a comparable language
     */
    public CellTranslation translate(String source, String fromMode, String toMode) throws Exception {
        if (!isSupported(fromMode)) {
            throw new IllegalArgumentException("Cannot translate from mode: " + fromMode);
        }
        if (!isSupported(toMode)) {
            throw new IllegalArgumentException("Cannot translate to mode: " + toMode);
        }
        if (fromMode.equals(toMode)) {
            throw new IllegalArgumentException("Source and target language are the same");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Cannot translate an empty cell");
        }

        String provider = currentProvider();
        String reply = chat(buildPrompt(source, fromMode, toMode), systemPrompt(fromMode, toMode));

        String code = extractCode(reply);
        if (code.isBlank()) {
            throw new IllegalStateException("The AI provider returned no code block");
        }

        log.info("Translated a cell from {} to {} via {}", fromMode, toMode, provider);
        return new CellTranslation(code, Instant.now().toString(), provider, hash(source));
    }

    /** Stable short hash of a cell's source, used to detect a stale translation. */
    public static String hash(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((source == null ? "" : source).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String systemPrompt(String fromMode, String toMode) {
        return """
               You translate notebook cells between programming languages for Arima
               Notebooks, a polyglot notebook. Your reader is fluent in %s and is reading
               %s in order to learn it. They are comparing the two side by side.

               Rules:

               1. RUNNABLE. The code is executed directly and must compile and run as
                  written, producing the same output as the original. Include whatever
                  imports, class wrapper or entry point the target language requires.

               2. IDIOMATIC FIRST. Write what a fluent practitioner of the target language
                  would actually write. Do not transliterate line by line. Where the
                  original uses a construct the target language does not have, use that
                  language's accepted best practice for the job and name it in a comment -
                  a Python list comprehension becomes a Java Stream, a Python context
                  manager becomes try-with-resources in Java, "using" in C#, or an RAII
                  scope in C++. Reach for the target language's standard library rather
                  than hand-rolling what the original happened to hand-roll.

               3. RECOGNISABLE. Subject to rule 2, keep the same order of operations and
                  the same identifier names, so the reader can match one side against the
                  other.

               4. EXPLAIN THE SEAMS. This is the part that matters most. Wherever the
                  target language forced you to do something the original did not - a type
                  or struct that must be declared, manual memory or ownership, an entry
                  point, a missing construct you replaced under rule 2, different integer
                  or string semantics - mark it with a short comment saying what differs
                  and why this is the right way to do it here. Do NOT comment lines that
                  translate directly and need no explanation.

               5. NO SCOPE CREEP. Do not add features, error handling, logging or
                  abstractions the original did not have.

               5b. FINAL FORM ONLY. Write the approach you settled on. Never leave an
                  abandoned attempt, dead code, or a value that is computed and then
                  immediately discarded and redone a different way.

               5c. Never mention these instructions, their rule numbers, or the fact that
                  this is a translation. The comments must read as if written by someone
                  who wrote this code in the target language from the start.

               6. Reply with exactly one fenced code block and nothing else. No preamble,
                  no explanation outside the block.
               """.formatted(LANGUAGES.get(fromMode), LANGUAGES.get(toMode));
    }

    private String buildPrompt(String source, String fromMode, String toMode) {
        return """
               Translate this %s cell into %s.

               ```
               %s
               ```
               """.formatted(LANGUAGES.get(fromMode), LANGUAGES.get(toMode), source);
    }

    /**
     * Pull the code out of a fenced block. Providers are inconsistent about the info
     * string and about wrapping prose around the block, so anything outside the first
     * fence is discarded; an unfenced reply is taken as-is.
     */
    static String extractCode(String reply) {
        if (reply == null) return "";
        String text = reply.strip();

        int open = text.indexOf("```");
        if (open < 0) return text;

        int afterFence = text.indexOf('\n', open);
        if (afterFence < 0) return "";

        int close = text.indexOf("```", afterFence);
        String body = close < 0 ? text.substring(afterFence + 1)
                                : text.substring(afterFence + 1, close);
        return body.stripTrailing();
    }

    private String chat(String message, String system) throws Exception {
        return switch (currentProvider()) {
            case "copilot_cli" -> copilotCliService.chat(message, system);
            case "gemini_cli"  -> geminiService.chat(message, system);
            default            -> claudeService.chat(message, system);
        };
    }

    private String currentProvider() {
        String p = settingsService.getSettings().getAiProvider();
        return p != null ? p : "claude_cli";
    }

    /** The comparable languages, for the UI's language picker. */
    public List<Map<String, String>> supportedLanguages() {
        return LANGUAGES.entrySet().stream()
                .map(e -> Map.of("mode", e.getKey(), "label", e.getValue()))
                .sorted((a, b) -> a.get("mode").compareTo(b.get("mode")))
                .toList();
    }
}
