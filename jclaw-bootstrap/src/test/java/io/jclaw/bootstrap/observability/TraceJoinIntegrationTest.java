// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.observability;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.ports.event.EventLog;
import io.jclaw.ports.event.JclawEvent;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.observability.TraceContext;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnStatus;
import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.domain.observability.RunTrace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identifiers a provider receives are the ones jclaw's own trace uses.
 *
 * <p>That is the whole point of propagating context: a collector holding the provider's span and
 * jclaw's OTLP export of the same run must be able to put them under one trace, and under the
 * same parent span rather than two that merely overlap in time.
 */
@SpringBootTest
class TraceJoinIntegrationTest {

    private static Path workspace;
    private static HttpServer server;
    private static int port;
    private static final List<String> traceparents = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-trace-it");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            traceparents.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);
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
        registry.add("jclaw.provider", () -> "local");
        registry.add("jclaw.local-base-url", () -> "http://127.0.0.1:" + port + "/v1");
        registry.add("jclaw.model", () -> "test-model");
    }

    @Autowired JclawRuntime runtime;
    @Autowired EventLog events;

    @Test
    @DisplayName("the provider's traceparent names the run's trace and a span the log also has")
    void providerJoinsTheRunTrace() {
        JclawRuntime.TurnResult result = runtime.submit(new ThreadId("trace"),
                ChatMessage.user("hello"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, result.status(), () -> String.valueOf(result.failureDetail()));

        assertEquals(1, traceparents.size(), "one model call, one header");
        TraceContext sent = TraceContext.parse(traceparents.get(0)).orElseThrow();
        assertEquals(RunTrace.traceId(result.run()), sent.traceId(),
                "a collector must be able to put the provider's span under this run's trace");
        assertTrue(sent.sampled());

        List<JclawEvent> log = events.readRun(result.run()).stream().map(EventLog.Entry::event).toList();
        List<String> spanIds = RunTrace.spans(result.run(), log).stream()
                .map(RunTrace.Span::spanId).toList();
        assertTrue(spanIds.contains(sent.spanId()),
                "the span id must be one the folded trace also has, not a third identifier: "
                        + spanIds + " does not contain " + sent.spanId());

        // The header carries identifiers and nothing else. A trace id derived from the run id by
        // SHA-256 is not the run id, and no prompt or credential goes with it.
        assertFalse(traceparents.get(0).contains(result.run().value()));
        assertTrue(traceparents.get(0).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"));
    }
}
