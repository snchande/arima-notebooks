package com.barista.shell;

import com.barista.model.BaristaSettings;
import com.barista.model.ExecutionResult;
import com.barista.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jdk.jshell.SourceCodeAnalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple JShell sessions (one per notebook).
 *
 * Publishes execution results via STOMP WebSocket so the browser
 * receives real-time output as code executes.
 *
 * WebSocket topic: /topic/shell/{sessionId}
 */
@Service
public class JShellManager {

    private static final Logger log = LoggerFactory.getLogger(JShellManager.class);

    private final Map<String, ShellSession> sessions = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final SettingsService settingsService;

    public JShellManager(SimpMessagingTemplate messagingTemplate, SettingsService settingsService) {
        this.messagingTemplate = messagingTemplate;
        this.settingsService = settingsService;
    }

    private long maxExecMs() {
        BaristaSettings s = settingsService.getSettings();
        return s.getMaxExecutionTimeMs() > 0 ? s.getMaxExecutionTimeMs() : 30_000L;
    }

    private int maxOutputLines() {
        BaristaSettings s = settingsService.getSettings();
        return s.getMaxOutputLines() > 0 ? s.getMaxOutputLines() : 1_000;
    }

    /**
     * Execute code in a session, broadcasting result via WebSocket.
     *
     * @param sessionId  Notebook/session identifier
     * @param code       Java code to execute
     * @param cellId     Cell that triggered this execution (may be null for console)
     * @return           Execution result
     */
    public ExecutionResult execute(String sessionId, String code, String cellId) {
        ShellSession session = getOrCreateSession(sessionId);

        log.debug("Executing in session {}: {}", sessionId,
                code.length() > 80 ? code.substring(0, 80) + "..." : code);

        // Stream incremental output to the browser while the cell runs (live progress for
        // long-running / high-output cells). Only for cell-bound runs — quiet/console runs skip it.
        String topic = "/topic/shell/" + sessionId;
        if (cellId != null) {
            session.setOutputSink(chunk -> messagingTemplate.convertAndSend(topic,
                    Map.of("type", "partial_output", "cellId", cellId, "text", chunk)));
        }

        ExecutionResult result;
        try {
            result = session.execute(code, cellId, maxExecMs(), maxOutputLines());
        } finally {
            session.setOutputSink(null);
        }

        // Broadcast to WebSocket subscribers
        messagingTemplate.convertAndSend(topic, result);

        return result;
    }

    /**
     * Execute code without broadcasting via WebSocket.
     */
    public ExecutionResult executeQuiet(String sessionId, String code) {
        return getOrCreateSession(sessionId).execute(code, null, maxExecMs(), maxOutputLines());
    }

    /**
     * Stop the cell currently running in a session (manual Stop button / interrupt).
     * Safe to call when nothing is running — it is a no-op.
     */
    public void interrupt(String sessionId) {
        ShellSession session = sessions.get(sessionId);
        if (session != null) session.requestStop();
    }

    /**
     * Get an existing session, or create a new one if it doesn't exist.
     */
    public ShellSession getOrCreateSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> {
            log.info("Creating new JShell session: {}", id);
            return new ShellSession(id);
        });
    }

    /**
     * Add a JAR to a session's classpath.
     */
    public void addJarToSession(String sessionId, String jarPath) {
        getOrCreateSession(sessionId).addJar(jarPath);
        log.info("Added JAR to session {}: {}", sessionId, jarPath);
    }

    /**
     * Restart a session (clears all variables and state).
     */
    public void restartSession(String sessionId) {
        ShellSession session = sessions.get(sessionId);
        if (session != null) {
            session.restart();
            log.info("Restarted JShell session: {}", sessionId);
        }
    }

    /**
     * Close and remove a session.
     */
    public void closeSession(String sessionId) {
        ShellSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            log.info("Closed JShell session: {}", sessionId);
        }
    }

    /**
     * Check if a session exists.
     */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Get all active session IDs.
     */
    public Set<String> getSessionIds() {
        return sessions.keySet();
    }

    /**
     * Get session info (classpath, execution count).
     */
    public ShellSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Return JShell completion suggestions for the given source prefix.
     * Uses the JShell SourceCodeAnalysis API built into the JDK.
     *
     * @param sessionId  Session to query (creates one if absent)
     * @param source     Full source text up to the cursor
     * @param cursor     Cursor position (end of source)
     * @return           List of completion strings
     */
    public List<String> complete(String sessionId, String source, int cursor) {
        ShellSession session = getOrCreateSession(sessionId);
        List<String> results = new ArrayList<>();
        try {
            int[] anchor = {0};
            List<SourceCodeAnalysis.Suggestion> suggestions =
                    session.getJShell().sourceCodeAnalysis()
                           .completionSuggestions(source, cursor, anchor);
            for (SourceCodeAnalysis.Suggestion s : suggestions) {
                results.add(s.continuation());
            }
        } catch (Exception e) {
            log.debug("Completion error for session {}: {}", sessionId, e.getMessage());
        }
        return results;
    }
}
