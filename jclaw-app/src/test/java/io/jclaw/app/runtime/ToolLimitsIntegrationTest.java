package io.jclaw.app.runtime;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.providers.mock.MockModelProvider.Script;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-tool limits at the product surface: an egress allowlist scoped to one capability, and a
 * per-capability rate limit, both from configuration.
 */
@SpringBootTest
class ToolLimitsIntegrationTest {

    private static Path workspace;
    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-limits-it");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "hello from loopback".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        // Loopback is normally private-network denied; open it so the per-tool list is what decides.
        registry.add("jclaw.allow-private-networks", () -> "true");
        registry.add("jclaw.tool-egress.[builtin.http_fetch]", () -> "127.0.0.1");
        registry.add("jclaw.tool-rate-limits.[builtin.echo]", () -> "1/1m");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of());
        }
    }

    @Autowired
    private JclawRuntime runtime;

    @Autowired
    private EventLog events;

    @Autowired
    private ModelProvider provider;

    private List<ContentBlock.ToolResult> toolResults(MockModelProvider mock) {
        return mock.lastRequest().orElseThrow().messages().stream()
                .filter(message -> message.role() == ChatMessage.Role.TOOL)
                .flatMap(message -> message.content().stream())
                .filter(ContentBlock.ToolResult.class::isInstance)
                .map(ContentBlock.ToolResult.class::cast)
                .toList();
    }

    @Test
    @DisplayName("a per-tool egress allowlist admits its host and refuses another, after the host checks")
    void perToolEgress() {
        MockModelProvider mock = (MockModelProvider) provider;
        mock.reprogram(List.of(
                new Script.ToolCall("f1", "builtin.http_fetch", Map.of("url", "http://127.0.0.1:" + port + "/")),
                new Script.ToolCall("f2", "builtin.http_fetch", Map.of("url", "http://localhost:" + port + "/")),
                new Script.Text("done")));

        JclawRuntime.TurnResult result =
                runtime.submit(new ThreadId("limits-egress"), "fetch both", new AtomicBoolean(false));

        assertEquals(TurnStatus.COMPLETED, result.status());
        List<ContentBlock.ToolResult> results = toolResults(mock);
        assertTrue(results.get(0).content().contains("hello from loopback"), "the allowlisted host is reachable");
        assertTrue(results.get(1).isError(), "a host outside the tool's list is refused");
        assertTrue(results.get(1).content().contains("tool_host_not_in_allowlist"), results.get(1).content());
    }

    @Test
    @DisplayName("a per-tool rate limit denies the call past the cap and audits it")
    void perToolRateLimit() {
        MockModelProvider mock = (MockModelProvider) provider;
        mock.reprogram(List.of(
                new Script.ToolCall("e1", "builtin.echo", Map.of("text", "one")),
                new Script.ToolCall("e2", "builtin.echo", Map.of("text", "two")),
                new Script.Text("done")));

        JclawRuntime.TurnResult result =
                runtime.submit(new ThreadId("limits-rate"), "echo twice", new AtomicBoolean(false));

        assertEquals(TurnStatus.COMPLETED, result.status());
        List<ContentBlock.ToolResult> results = toolResults(mock);
        assertTrue(results.get(0).content().contains("one"));
        assertTrue(results.get(1).isError());
        assertTrue(results.get(1).content().contains("rate_limited"), results.get(1).content());
        assertTrue(results.get(1).content().contains("1 per 1m"));

        long denied = events.readRun(result.run()).stream()
                .map(EventLog.Entry::event)
                .filter(JclawEvent.CapabilityInvoked.class::isInstance)
                .map(JclawEvent.CapabilityInvoked.class::cast)
                .filter(e -> e.capability().value().equals("builtin.echo") && e.outcome().equals("denied"))
                .count();
        assertEquals(1, denied);
    }
}
