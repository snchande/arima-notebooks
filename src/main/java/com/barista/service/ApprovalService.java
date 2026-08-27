package com.barista.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds code that arrived from off this machine until the person sitting at it says
 * yes.
 *
 * <p>When local-network access is switched on, a CLI or an agent on another device
 * can reach the execution endpoints - and those run code as the user who started the
 * server. Convenience for the owner and a foothold for anyone else are the same
 * feature, so remote work does not run on arrival: it waits here, the browser is
 * brought to the front with the code on screen, and nothing happens until it is
 * approved.
 *
 * <p>The calling request blocks meanwhile, so the remote caller sees a slow response
 * rather than a silent success - it cannot tell itself the code ran when it did not.
 * A request nobody answers is denied when the timeout expires; the default is to
 * refuse, never to assume.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** How long a remote caller waits before the unanswered request is refused. */
    @Value("${barista.network.approval-timeout-ms:180000}")
    private long timeoutMs;

    public enum State { PENDING, APPROVED, DENIED, EXPIRED }

    /** One piece of work waiting for the local user's decision. */
    public static final class Request {
        public final String id = UUID.randomUUID().toString();
        public final String origin;
        public final String action;
        public final String language;
        public final String code;
        public final String target;
        public final String requestedAt = Instant.now().toString();
        final CountDownLatch decided = new CountDownLatch(1);
        volatile State state = State.PENDING;

        Request(String origin, String action, String language, String code, String target) {
            this.origin = origin;
            this.action = action;
            this.language = language;
            this.code = code == null ? "" : code;
            this.target = target == null ? "" : target;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("origin", origin);
            m.put("action", action);
            m.put("language", language);
            m.put("code", code);
            m.put("target", target);
            m.put("requestedAt", requestedAt);
            m.put("state", state.name().toLowerCase(Locale.ROOT));
            return m;
        }
    }

    private final Map<String, Request> pending = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Called when a request appears, so the UI can be raised in front of the user. */
    public synchronized void onNewRequest(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Register the work and block until the local user decides.
     *
     * @return true if it may proceed
     */
    public boolean awaitApproval(String origin, String action, String language,
                                 String code, String target) {
        Request req = new Request(origin, action, language, code, target);
        pending.put(req.id, req);

        log.warn("[Approval] {} from {} is waiting for you to review it ({})",
                action, origin, req.id);
        notifyListeners();

        boolean answered;
        try {
            answered = req.decided.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(req.id);
            return false;
        }

        pending.remove(req.id);
        if (!answered) {
            req.state = State.EXPIRED;
            log.warn("[Approval] {} from {} expired unanswered and was refused", action, origin);
            return false;
        }
        boolean ok = req.state == State.APPROVED;
        log.warn("[Approval] {} from {} was {}", action, origin, ok ? "APPROVED" : "DENIED");
        return ok;
    }

    public List<Map<String, Object>> listPending() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Request r : pending.values()) out.add(r.toMap());
        out.sort((a, b) -> a.get("requestedAt").toString().compareTo(b.get("requestedAt").toString()));
        return out;
    }

    public boolean decide(String id, boolean approve) {
        Request r = pending.get(id);
        if (r == null) return false;
        r.state = approve ? State.APPROVED : State.DENIED;
        r.decided.countDown();
        return true;
    }

    /** Refuse everything outstanding - used when network access is switched off. */
    public void denyAll() {
        for (Request r : pending.values()) {
            r.state = State.DENIED;
            r.decided.countDown();
        }
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    private void notifyListeners() {
        List<Runnable> copy;
        synchronized (this) { copy = new ArrayList<>(listeners); }
        for (Runnable l : copy) {
            try {
                l.run();
            } catch (Exception e) {
                log.debug("Approval listener failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Bring the review screen in front of the user.
     *
     * <p>Deliberately not {@code java.awt.Desktop}: the server runs headless in
     * background mode, where Desktop is unavailable and would throw. Handing the URL
     * to the OS shell both opens it and raises the window.
     */
    public static void raiseBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd;
        if (os.contains("win")) {
            cmd = List.of("cmd", "/c", "start", "", url);
        } else if (os.contains("mac")) {
            cmd = List.of("open", url);
        } else {
            cmd = List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            log.warn("[Approval] Could not open the browser for review: {}. Open {} yourself.",
                    e.getMessage(), url);
        }
    }
}
