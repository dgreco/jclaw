package io.jclaw.tools.mcp;

import io.jclaw.contracts.Result;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A remote server over MCP's streamable HTTP transport.
 *
 * <p>Every message is a POST of one JSON-RPC envelope. The server answers in whichever shape it
 * prefers, and both are handled: {@code application/json} with the response inline, or
 * {@code text/event-stream} carrying the response as SSE {@code data:} frames, which is how a
 * server streams progress notifications before its result. A notification gets no reply and the
 * usual {@code 202} says so.
 *
 * <p>The session id the server hands back on initialize is echoed on every later request, since a
 * stateful server uses it to recognise the conversation.
 *
 * <p>Two things this transport does not do, deliberately. It does not follow redirects: an MCP
 * endpoint that moves is configuration to fix, not a hop to take, and a redirect is how a
 * validated URL becomes an unvalidated one. And it holds no credential of its own — the caller
 * supplies the ready {@code Authorization} header value, so the vault stays above the tool layer.
 */
public final class HttpTransport implements McpTransport {

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client;
    private final URI endpoint;
    private final Optional<String> authorization;
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private volatile boolean closed;

    /**
     * @param endpoint      the server's MCP endpoint, already checked by the host's egress guard
     * @param authorization a complete {@code Authorization} header value, or empty
     */
    public HttpTransport(URI endpoint, Optional<String> authorization) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Result<Map<String, Object>, String> send(
            Map<String, Object> envelope, Optional<Long> expectId, Duration timeout) {

        if (closed) {
            return Result.err("transport_closed");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(envelope), StandardCharsets.UTF_8));
        authorization.ifPresent(value -> request.header("Authorization", value));
        // The run's trace, when one is current, so an MCP server's own spans join jclaw's.
        io.jclaw.contracts.observability.TraceContext.current()
                .ifPresent(trace -> request.header("traceparent", trace.traceparent()));
        String session = sessionId.get();
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }

        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            return Result.err("server_unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("interrupted");
        } catch (RuntimeException e) {
            return Result.err("request_rejected");
        }

        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId.compareAndSet(null, id));

        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status == 401 || status == 403) {
                return Result.err("server_unauthorized");
            }
            if (status >= 400) {
                return Result.err("server_http_" + status);
            }
            if (expectId.isEmpty()) {
                return Result.ok(Map.of());
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("application/json").toLowerCase(Locale.ROOT);
            return contentType.startsWith("text/event-stream")
                    ? readEventStream(body, expectId.get())
                    : readJson(body, expectId.get());
        } catch (IOException e) {
            return Result.err("server_read_failed");
        }
    }

    @SuppressWarnings("unchecked")
    private Result<Map<String, Object>, String> readJson(InputStream body, long id) throws IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES);
        if (bytes.length == 0) {
            return Result.err("server_empty_response");
        }
        Map<String, Object> message;
        try {
            message = mapper.readValue(bytes, Map.class);
        } catch (RuntimeException e) {
            return Result.err("server_malformed_response");
        }
        Result<Map<String, Object>, String> matched = McpProtocol.matchResponse(message, id);
        return matched == null ? Result.err("server_response_mismatched") : matched;
    }

    /**
     * Reads SSE frames until the awaited response arrives.
     *
     * <p>Only the {@code data:} field matters here: MCP puts one JSON-RPC message in each frame,
     * and everything before the awaited id is a progress notification the loop does not model.
     */
    @SuppressWarnings("unchecked")
    private Result<Map<String, Object>, String> readEventStream(InputStream body, long id) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        long read = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            read += line.length() + 1;
            if (read > MAX_RESPONSE_BYTES) {
                return Result.err("server_response_too_large");
            }
            if (line.isEmpty()) {
                if (data.isEmpty()) {
                    continue;
                }
                Result<Map<String, Object>, String> matched = frame(data.toString(), id);
                data.setLength(0);
                if (matched != null) {
                    return matched;
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
            // id:, event:, retry:, and comments carry nothing this client needs.
        }
        // A stream that ended may still hold an unterminated final frame.
        Result<Map<String, Object>, String> last = data.isEmpty() ? null : frame(data.toString(), id);
        return last == null ? Result.err("server_closed_stream") : last;
    }

    @SuppressWarnings("unchecked")
    private Result<Map<String, Object>, String> frame(String data, long id) {
        try {
            return McpProtocol.matchResponse(mapper.readValue(data, Map.class), id);
        } catch (RuntimeException e) {
            return null; // not protocol; skip the frame
        }
    }

    @Override
    public boolean isAlive() {
        return !closed;
    }

    @Override
    public String describe() {
        return "http " + endpoint + (authorization.isPresent() ? " (authenticated)" : "");
    }

    @Override
    public void close() {
        closed = true;
    }
}
