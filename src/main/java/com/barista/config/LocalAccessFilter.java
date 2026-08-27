package com.barista.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.barista.service.ApprovalService;
import com.barista.service.SettingsService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Refuses any request that did not come from this machine.
 *
 * <p>Arima runs code. {@code POST /api/shell/execute} and the MCP tools execute
 * whatever they are given, in eight languages, as the user who started the server,
 * and in the default {@code local} auth mode nothing authenticates the caller. That
 * is the correct trade for a tool you run on your own laptop, and completely wrong
 * for anything reachable from elsewhere.
 *
 * <p>The listener is already bound to loopback ({@code server.address}), so this is
 * defence in depth rather than the primary control: it also catches a request
 * forwarded by a proxy, a port forward, or a future configuration change that widens
 * the bind without anyone noticing.
 *
 * <p>It additionally checks the {@code Host} header. A browser can be pointed at a
 * hostname that resolves to 127.0.0.1 while the page itself is served from an
 * attacker's origin - DNS rebinding - and the socket then genuinely is loopback.
 * Requiring Host to be a loopback name closes that.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAccessFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(LocalAccessFilter.class);

    /** Logged once per distinct peer, so a scanner cannot flood the log. */
    private volatile String lastRejectedPeer = "";

    // ObjectProvider, not a hard dependency: this filter must still construct in a
    // slice context where those beans are absent, and when they are it falls back to
    // the closed position - network access off - rather than failing open.
    private final ObjectProvider<SettingsService> settingsService;
    private final ObjectProvider<ApprovalService> approvalService;

    public LocalAccessFilter(ObjectProvider<SettingsService> settingsService,
                             ObjectProvider<ApprovalService> approvalService) {
        this.settingsService = settingsService;
        this.approvalService = approvalService;
    }

    private boolean networkAccessEnabled() {
        SettingsService s = settingsService.getIfAvailable();
        return s != null && s.getSettings().isNetworkAccessEnabled();
    }

    /**
     * Endpoints that run code or change what will run. Reached from another machine,
     * these are held for the local user's approval; everything else (reading a
     * notebook, the UI itself) is served normally.
     */
    private static boolean isExecution(String path) {
        if (path == null) return false;
        // Never gate the approval endpoints themselves: a remote caller cannot reach
        // them to approve its own work, and gating them would deadlock the local user.
        if (path.startsWith("/api/approvals")) return false;
        return path.startsWith("/api/shell/execute")
            || path.startsWith("/api/shell/run-to-here")
            || path.startsWith("/api/mcp/")
            || path.startsWith("/api/agents/run")
            || path.startsWith("/actuator/shutdown");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String peer = request.getRemoteAddr();
        if (!isLoopback(peer)) {
            if (!networkAccessEnabled()) {
                deny(res, peer, "remote address " + peer + " is not loopback");
                return;
            }
            // Network access is on, so this peer is allowed to ask - but anything that
            // executes waits for the person at the machine to agree to it.
            if (isExecution(req.getRequestURI())) {
                String body = readBody(req);
                ApprovalService approvals = approvalService.getIfAvailable();
                if (approvals == null) {
                    deny(res, peer, "the approval gate is unavailable, so remote work cannot run");
                    return;
                }
                boolean ok = approvals.awaitApproval(
                        peer, req.getMethod() + " " + req.getRequestURI(),
                        guessLanguage(body), body, req.getRequestURI());
                if (!ok) {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType("application/json");
                    res.getWriter().write(
                        "{\"error\":\"Refused by the user at the machine running Arima.\"}");
                    return;
                }
                chain.doFilter(new CachedBodyRequest(req, body), response);
                return;
            }
        }
        // The Host check exists to stop DNS rebinding against a loopback-only server.
        // Once the user has deliberately opened Arima to the network, a legitimate
        // request genuinely carries the LAN address or hostname, and rebinding buys an
        // attacker nothing they cannot already do directly - the approval gate is what
        // protects execution there.
        if (!networkAccessEnabled() && !isLoopbackHost(req.getHeader("Host"))) {
            deny(res, peer, "Host header '" + req.getHeader("Host") + "' is not a loopback name");
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse res, String peer, String why) throws IOException {
        if (!peer.equals(lastRejectedPeer)) {
            lastRejectedPeer = peer;
            log.warn("[Security] Refused a non-local request: {}. Arima executes code and is "
                   + "reachable only from this machine.", why);
        }
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.getWriter().write("""
                {"error":"Arima Notebooks only accepts requests from this machine.",\
                "detail":"It executes code with your user account, so it is bound to \
                localhost. See docs/SECURITY.md."}""");
    }

    /**
     * A servlet body can be read once. It has to be read here to show the user what
     * they are approving, so it is cached and replayed to the controller afterwards.
     */
    private static String readBody(HttpServletRequest req) throws IOException {
        try (BufferedReader r = req.getReader()) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Best effort, for the review screen's syntax label only. */
    static String guessLanguage(String body) {
        if (body == null) return "";
        int i = body.indexOf("\"mode\"");
        if (i < 0) return "";
        int q = body.indexOf('"', body.indexOf(':', i) + 1);
        if (q < 0) return "";
        int end = body.indexOf('"', q + 1);
        return end > q ? body.substring(q + 1, end) : "";
    }

    /** Replays the cached body so the controller still sees its request intact. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, String cached) {
            super(request);
            this.body = cached.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** Public so callers outside this package can enforce the same rule. */
    public static boolean isLoopback(String addr) {
        if (addr == null || addr.isBlank()) return false;
        try {
            return InetAddress.getByName(addr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * The Host header carries the name the client asked for, and a port. Only names
     * that mean "this machine" are accepted; anything else is a rebinding attempt or
     * a proxy we do not want to serve.
     */
    static boolean isLoopbackHost(String host) {
        // A MISSING Host is allowed; a WRONG one is not. The peer check has already
        // proved the connection came from this machine, and the rebinding attack this
        // guards against is necessarily driven by a browser, which always sends Host.
        // Rejecting its absence would only break local HTTP/1.0 clients and tooling.
        if (host == null || host.isBlank()) return true;

        String h = host.trim().toLowerCase(Locale.ROOT);

        // Strip the port. IPv6 literals are bracketed: [::1]:8585
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close < 0) return false;
            // Only a port may follow the bracket. Without this, "[::1].evil.com"
            // parsed as the loopback literal and everything after it was ignored.
            String after = h.substring(close + 1);
            if (!after.isEmpty() && !after.matches(":\\d+")) return false;
            h = h.substring(1, close);
        } else {
            int colon = h.lastIndexOf(':');
            if (colon > -1) h = h.substring(0, colon);
        }

        if (h.equals("localhost") || h.endsWith(".localhost")) return true;

        // Only a numeric literal is resolved. Handing the Host header to
        // InetAddress.getByName would perform a DNS lookup on a value the attacker
        // controls - and a domain of theirs pointed at 127.0.0.1 would then be
        // accepted, which is precisely the rebinding this check exists to stop.
        return isNumericLiteral(h) && isLoopback(h);
    }

    /** True only for an IPv4 or IPv6 literal, so no name is ever resolved. */
    static boolean isNumericLiteral(String h) {
        if (h.indexOf(':') >= 0) {
            // IPv6: hex groups and separators only.
            return h.chars().allMatch(c ->
                    Character.digit(c, 16) >= 0 || c == ':' || c == '.' || c == '%');
        }
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
