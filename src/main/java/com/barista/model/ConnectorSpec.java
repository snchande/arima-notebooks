package com.barista.model;

import java.util.List;
import java.util.Map;

/**
 * An external MCP server that Arima connects <em>to</em>.
 *
 * The mirror image of {@link com.barista.controller.McpController}: that one makes Arima a tool
 * server for other clients, a connector makes some other server's tools usable from here. A
 * connector notebook is a normal {@link Notebook} with {@code metadata.kind = "connector"} and a
 * {@code metadata.connector} block; {@link com.barista.service.ConnectorService} projects it into
 * this shape, probes it for its tool list, and can write it into an agentic CLI's
 * {@code .mcp.json}.
 *
 * Two transports, matching the MCP specification:
 *
 * <ul>
 *   <li>{@code stdio} — Arima launches {@code command} as a subprocess and speaks JSON-RPC over
 *       its pipes. No network at all.</li>
 *   <li>{@code sse} — Arima opens {@code url}. A loopback address stays local; anything else is a
 *       new outbound host and goes through the approval gate every time.</li>
 * </ul>
 *
 * @param id          notebook id
 * @param name        display name
 * @param slug        kebab-cased name — the server key in a generated {@code .mcp.json}
 * @param description one-line description
 * @param transport   {@code stdio} or {@code sse}
 * @param command     argv for a stdio server (empty for sse)
 * @param url         endpoint for an sse server (null for stdio)
 * @param env         extra environment for a stdio subprocess
 * @param enabled     whether agents may reach it
 * @param notes       the notebook's markdown cells — setup notes, not sent anywhere
 */
public record ConnectorSpec(String id, String name, String slug, String description,
                            String transport, List<String> command, String url,
                            Map<String, String> env, boolean enabled, String notes) {

    public static final String STDIO = "stdio";
    public static final String SSE = "sse";

    /** Normalize a transport string; anything unrecognized is stdio, the one that needs no network. */
    public static String transportOf(String s) {
        return (s != null && s.trim().equalsIgnoreCase(SSE)) ? SSE : STDIO;
    }

    public boolean isStdio() { return STDIO.equals(transport); }

    /** Whether this connector reaches outside the machine — the thing the approval gate exists for. */
    public boolean isRemote() {
        if (isStdio() || url == null) return false;
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return true;
            return !(host.equals("localhost") || host.equals("127.0.0.1")
                     || host.equals("::1") || host.equals("[::1]"));
        } catch (IllegalArgumentException e) {
            return true;   // unparseable: treat as remote, so it needs approval
        }
    }

    /** A one-line summary of where this connector points, for logs and cards. */
    public String endpoint() {
        return isStdio() ? String.join(" ", command) : String.valueOf(url);
    }
}
