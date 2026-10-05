package com.barista.service;

import com.barista.model.ConnectorSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The client half of MCP: speaks JSON-RPC 2.0 to an external MCP server so a
 * {@link ConnectorSpec} can be probed and its tools called.
 *
 * Two transports, each a short-lived conversation rather than a held-open session — a connector is
 * probed when you ask and called when a tool is invoked, so there is no background connection to
 * leak and nothing to reconnect after a restart:
 *
 * <ul>
 *   <li><b>stdio</b> — launch the server's {@code command} with
 *       {@link ProcessBuilder}{@code (List<String>)} (never a shell string) and exchange
 *       newline-delimited JSON-RPC over its pipes.</li>
 *   <li><b>sse</b> — open the SSE stream, read the {@code endpoint} event the server sends on
 *       connect, then POST requests there and read the replies off the stream.</li>
 * </ul>
 *
 * Reaching outside the machine is gated elsewhere: {@link ConnectorService} asks
 * {@link ApprovalService} before this class is ever handed a remote URL.
 */
@Service
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int PROCESS_TIMEOUT_SEC = 30;

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** What a probe found: the server's own identity plus the tools it offers. */
    public record Probe(String serverName, String serverVersion, List<Tool> tools) {}

    /** One tool as the remote server describes it. */
    public record Tool(String name, String description, Map<String, Object> inputSchema) {}

    // -- Public verbs -----------------------------------------------------------

    /** Initialize and list tools. Throws with an actionable message if the server cannot be reached. */
    public Probe probe(ConnectorSpec spec) throws IOException {
        return spec.isStdio() ? probeStdio(spec) : probeSse(spec);
    }

    /** Call one of the server's tools and return its text content, flattened. */
    public String callTool(ConnectorSpec spec, String toolName, Map<String, Object> args)
            throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", args == null ? Map.of() : args);

        JsonNode result = spec.isStdio()
                ? stdioExchange(spec, List.of(initialize(1), request(2, "tools/call", params)), 2)
                : sseExchange(spec, List.of(initialize(1), request(2, "tools/call", params)), 2);
        return flattenContent(result);
    }

    // -- stdio ------------------------------------------------------------------

    private Probe probeStdio(ConnectorSpec spec) throws IOException {
        if (spec.command() == null || spec.command().isEmpty()) {
            throw new IOException("This connector has no command. Set one in the banner, "
                    + "e.g. npx -y @modelcontextprotocol/server-filesystem /some/path");
        }
        JsonNode init = stdioExchange(spec,
                List.of(initialize(1), request(2, "tools/list", Map.of())), 1);
        JsonNode tools = stdioExchange(spec,
                List.of(initialize(1), request(2, "tools/list", Map.of())), 2);
        return new Probe(serverName(init), serverVersion(init), readTools(tools));
    }

    /**
     * Run one stdio conversation: launch the server, write each request, read until the reply with
     * {@code wantId} arrives, then shut it down.
     */
    private JsonNode stdioExchange(ConnectorSpec spec, List<Map<String, Object>> requests, int wantId)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(windowsSafe(spec.command()));   // argv list, never a shell string
        pb.redirectErrorStream(false);
        if (spec.env() != null) pb.environment().putAll(spec.env());

        Process p = pb.start();
        try (BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

            for (Map<String, Object> req : requests) {
                out.write(mapper.writeValueAsString(req));
                out.write("\n");
                out.flush();
            }

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(PROCESS_TIMEOUT_SEC);
            String line;
            while (System.currentTimeMillis() < deadline && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode msg;
                try {
                    msg = mapper.readTree(line);
                } catch (Exception notJson) {
                    continue;   // servers sometimes log to stdout; skip anything that is not a message
                }
                if (msg.path("id").asInt(-1) != wantId) continue;
                return resultOrThrow(msg, spec);
            }
            throw new IOException(stderrHint(p, "the server sent no reply within "
                    + PROCESS_TIMEOUT_SEC + "s"));
        } finally {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    /** The Node launchers Windows ships as batch scripts rather than executables. */
    private static final List<String> WINDOWS_BATCH_LAUNCHERS =
            List.of("npm", "npx", "pnpm", "yarn", "bun", "deno", "tsc");

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * On Windows these are {@code .cmd} batch scripts, and {@link ProcessBuilder} only resolves
     * them off PATH when given the extension — so {@code npx} fails with
     * "CreateProcess error=2" while {@code npx.cmd} works. The same fix
     * {@link NpmPackageService} already applies, here for a connector's own command.
     */
    private List<String> windowsSafe(List<String> command) {
        if (!IS_WINDOWS || command.isEmpty()) return command;
        String program = command.get(0);
        if (program.contains(".") || program.contains("/") || program.contains("\\")
                || !WINDOWS_BATCH_LAUNCHERS.contains(program.toLowerCase())) {
            return command;
        }
        List<String> fixed = new ArrayList<>(command);
        fixed.set(0, program + ".cmd");
        return fixed;
    }

    /** Pull whatever the subprocess wrote to stderr into the error message — that is where the cause is. */
    private String stderrHint(Process p, String base) {
        try {
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return err.isBlank() ? base : base + ":\n" + err;
        } catch (IOException e) {
            return base;
        }
    }

    // -- sse --------------------------------------------------------------------

    private Probe probeSse(ConnectorSpec spec) throws IOException {
        JsonNode init = sseExchange(spec,
                List.of(initialize(1), request(2, "tools/list", Map.of())), 1);
        JsonNode tools = sseExchange(spec,
                List.of(initialize(1), request(2, "tools/list", Map.of())), 2);
        return new Probe(serverName(init), serverVersion(init), readTools(tools));
    }

    /**
     * Run one SSE conversation. The server's first event names the endpoint to POST to; replies
     * come back as {@code message} events on the open stream.
     */
    private JsonNode sseExchange(ConnectorSpec spec, List<Map<String, Object>> requests, int wantId)
            throws IOException {
        if (spec.url() == null || spec.url().isBlank()) {
            throw new IOException("This connector has no URL. Set one in the banner, "
                    + "e.g. http://127.0.0.1:3000/sse");
        }
        HttpResponse<java.io.InputStream> stream;
        try {
            stream = http.send(
                    HttpRequest.newBuilder(URI.create(spec.url()))
                            .timeout(TIMEOUT)
                            .header("Accept", "text/event-stream")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to " + spec.url());
        }
        if (stream.statusCode() >= 400) {
            throw new IOException("The server answered " + stream.statusCode() + " at " + spec.url());
        }

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {

            String postUrl = readEndpointEvent(in, spec.url());
            for (Map<String, Object> req : requests) {
                post(postUrl, req);
            }

            long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
            String line;
            while (System.currentTimeMillis() < deadline && (line = in.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                JsonNode msg;
                try {
                    msg = mapper.readTree(payload);
                } catch (Exception notJson) {
                    continue;
                }
                if (msg.path("id").asInt(-1) != wantId) continue;
                return resultOrThrow(msg, spec);
            }
            throw new IOException("No reply from " + spec.url() + " within " + TIMEOUT.toSeconds() + "s");
        }
    }

    /** Read the SSE {@code endpoint} event and resolve it against the stream URL. */
    private String readEndpointEvent(BufferedReader in, String streamUrl) throws IOException {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        String line;
        while (System.currentTimeMillis() < deadline && (line = in.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String value = line.substring(5).trim();
            if (value.isEmpty()) continue;
            return URI.create(streamUrl).resolve(value).toString();
        }
        throw new IOException("The server at " + streamUrl
                + " never sent its endpoint event — it may not be an MCP SSE server.");
    }

    private void post(String url, Map<String, Object> body) throws IOException {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) {
                throw new IOException("POST " + url + " answered " + r.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while posting to " + url);
        }
    }

    // -- JSON-RPC helpers -------------------------------------------------------

    private Map<String, Object> initialize(int id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "arima-notebooks", "version", "1.0.0"));
        return request(id, "initialize", params);
    }

    private Map<String, Object> request(int id, String method, Map<String, Object> params) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.put("params", params);
        return req;
    }

    private JsonNode resultOrThrow(JsonNode msg, ConnectorSpec spec) throws IOException {
        if (msg.has("error")) {
            JsonNode e = msg.get("error");
            throw new IOException(spec.name() + " refused the request: "
                    + e.path("message").asText(e.toString()));
        }
        return msg.path("result");
    }

    private String serverName(JsonNode init) {
        String n = init.path("serverInfo").path("name").asText("");
        return n.isBlank() ? "(unnamed server)" : n;
    }

    private String serverVersion(JsonNode init) {
        return init.path("serverInfo").path("version").asText("");
    }

    @SuppressWarnings("unchecked")
    private List<Tool> readTools(JsonNode result) {
        List<Tool> out = new ArrayList<>();
        for (JsonNode t : result.path("tools")) {
            Map<String, Object> schema = t.has("inputSchema")
                    ? mapper.convertValue(t.get("inputSchema"), Map.class)
                    : Map.of();
            out.add(new Tool(t.path("name").asText(""), t.path("description").asText(""), schema));
        }
        return out;
    }

    /** MCP returns content blocks; agents want the text. */
    private String flattenContent(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            String text = block.path("text").asText("");
            if (!text.isBlank()) sb.append(text).append('\n');
        }
        if (sb.isEmpty()) return result.toString();
        if (result.path("isError").asBoolean(false)) sb.insert(0, "The tool reported an error:\n");
        return sb.toString().stripTrailing();
    }
}
