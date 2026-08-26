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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
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

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String peer = request.getRemoteAddr();
        if (!isLoopback(peer)) {
            deny(res, peer, "remote address " + peer + " is not loopback");
            return;
        }
        if (!isLoopbackHost(req.getHeader("Host"))) {
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

    static boolean isLoopback(String addr) {
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
