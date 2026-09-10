// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.mcp;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.domain.mcp.SamplingPolicy;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.storage.event.JsonlEventLog;
import io.jclaw.storage.jsonl.JsonlFile;
import io.jclaw.tools.mcp.HttpTransport;
import io.jclaw.tools.mcp.McpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reverse direction end to end: a server asks jclaw to sample a model while a tool call is
 * in flight, jclaw answers on its own request, and the tool call still returns.
 */
class McpSamplingTest {

    @TempDir Path dir;

    private HttpServer server;
    private String endpoint;
    /** Every JSON body the server received, so the test can see the sampling reply arrive. */
    private final List<String> received = new CopyOnWriteArrayList<>();

    private static byte[] sse(String... frames) {
        StringBuilder out = new StringBuilder();
        for (String frame : frames) {
            out.append("data: ").append(frame).append("\n\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            received.add(body);

            byte[] answer;
            String contentType = "application/json";
            if (body.contains("\"initialize\"")) {
                answer = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\","
                        + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"}}}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (body.contains("tools/call")) {
                // Ask jclaw to sample, then answer the tool call. Both in one SSE stream, which
                // is exactly the shape that made this worth implementing in the reader.
                contentType = "text/event-stream";
                answer = sse(
                        "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"sampling/createMessage\","
                                + "\"params\":{\"messages\":[{\"role\":\"user\",\"content\":"
                                + "{\"type\":\"text\",\"text\":\"name this file\"}}],\"maxTokens\":50}}",
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"tool done\"}]}}");
            } else if (body.contains("\"id\":99")) {
                // jclaw's answer to the sampling request; acknowledge and say nothing.
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            } else {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private EventLog events() {
        return new JsonlEventLog(new JsonlFile(dir.resolve("events.jsonl")));
    }

    private McpSampling sampling(EventLog events, int limit) {
        ModelProvider provider = MockModelProvider.alwaysReplying("a-good-name.txt");
        return new McpSampling("srv", provider, events, Clock.systemUTC(), "m", 512, limit);
    }

    @Test
    @DisplayName("a server sampling mid-call gets an answer, and the tool call still returns")
    void answersSamplingDuringAToolCall() {
        EventLog events = events();
        McpSampling handler = sampling(events, 5);
        HttpTransport transport = new HttpTransport(URI.create(endpoint), Optional.<String>empty());
        transport.onServerRequest(handler);

        McpClient client = McpClient.connect("srv", transport).orElseThrow();
        String result = client.callTool("anything", Map.of()).orElseThrow();

        assertTrue(result.contains("tool done"), result);
        assertEquals(1, handler.calls());
        assertTrue(received.stream().anyMatch(body ->
                        body.contains("\"id\":99") && body.contains("a-good-name.txt")),
                "the sampling reply reached the server: " + received);

        assertTrue(client.offers().contains("tools"));
        assertTrue(events.readRun(McpSampling.samplingRun("srv")).stream()
                        .map(EventLog.Entry::event)
                        .anyMatch(e -> e instanceof JclawEvent.ModelCalled),
                "a sampled call is audited; an MCP server must not be the one caller whose "
                        + "spending appears nowhere");
    }

    @Test
    @DisplayName("without a handler the client never advertises sampling, and a request is refused")
    void noHandlerNoCapability() {
        HttpTransport transport = new HttpTransport(URI.create(endpoint), Optional.<String>empty());
        McpClient client = McpClient.connect("srv", transport).orElseThrow();

        String initialize = received.get(0);
        assertFalse(initialize.contains("sampling"),
                "advertising a capability we would refuse is worse than not advertising it: "
                        + initialize);

        assertTrue(client.callTool("anything", Map.of()).isOk(),
                "an unsolicited sampling request must not fail the call in flight");
        assertTrue(received.stream().anyMatch(body ->
                        body.contains("\"id\":99") && body.contains("method_not_supported")),
                "the server is told the method does not exist here: " + received);
    }

    @Test
    @DisplayName("a server past its cap is refused, and the refusal reaches it")
    void capIsEnforced() {
        McpSampling handler = sampling(events(), 0);
        assertTrue(handler.answer(McpSampling.METHOD, Map.of(
                        "messages", List.of(Map.of("role", "user",
                                "content", SamplingPolicy.textBlock("hi"))))).isErr());
        assertEquals("sampling_budget_exhausted", handler.answer(McpSampling.METHOD, Map.of(
                        "messages", List.of(Map.of("role", "user",
                                "content", SamplingPolicy.textBlock("hi")))))
                .errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("only sampling is answered; any other server request is not supported")
    void onlySamplingIsAnswered() {
        McpSampling handler = sampling(events(), 5);
        assertEquals("method_not_supported",
                handler.answer("roots/list", Map.of()).errorAsOptional().orElseThrow());
        assertEquals(0, handler.calls(), "a method we do not answer costs no budget");
    }
}
