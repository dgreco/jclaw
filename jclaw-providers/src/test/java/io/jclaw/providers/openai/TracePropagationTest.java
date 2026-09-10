// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.providers.openai;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.observability.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A model request carries W3C trace context when the interpreter opened a scope for it, and
 * carries nothing when it did not.
 */
class TracePropagationTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> seen = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        seen.set(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void call() {
        new OpenAiCompatibleModelProvider("local", baseUrl, Optional.empty())
                .complete(ModelRequest.of("m", "", List.of(ChatMessage.user("hi")), 32));
    }

    @Test
    @DisplayName("an open scope becomes a traceparent header the server can parse")
    void sendsTraceparent() {
        TraceContext context = new TraceContext(TRACE, SPAN, true);
        try (var ignored = TraceContext.open(context)) {
            call();
        }
        assertEquals(context.traceparent(), seen.get());
        assertEquals(Optional.of(context), TraceContext.parse(seen.get()),
                "what arrives is what a collector would parse");
    }

    @Test
    @DisplayName("no scope, no header — a request is not given a trace it does not belong to")
    void sendsNothingWithoutAScope() {
        call();
        assertNull(seen.get());
    }
}
