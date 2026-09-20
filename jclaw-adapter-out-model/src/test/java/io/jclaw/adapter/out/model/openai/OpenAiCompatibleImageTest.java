// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.model.openai;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ContentBlock;
import io.jclaw.ports.model.ModelExchange.ModelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** An image block travels as a data-URL image part alongside the text part. */
class OpenAiCompatibleImageTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"a cat\"},\"finish_reason\":\"stop\"}]}"
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

    @Test
    @DisplayName("a user message with an image is sent as content parts")
    void sendsImagePart() {
        ChatMessage message = new ChatMessage(ChatMessage.Role.USER, List.of(
                new ContentBlock.Text("what is this?"),
                new ContentBlock.Image("image/png", "AAAA")));
        new OpenAiCompatibleModelProvider("local", baseUrl, Optional.empty())
                .complete(ModelRequest.of("m", "", List.of(message), 32));

        String body = lastBody.get();
        assertTrue(body.contains("\"type\":\"text\"") && body.contains("\"text\":\"what is this?\""), body);
        assertTrue(body.contains("\"type\":\"image_url\""), body);
        assertTrue(body.contains("\"url\":\"data:image/png;base64,AAAA\""), body);
    }

    @Test
    @DisplayName("a text-only message keeps the plain string content servers expect")
    void plainTextStaysAString() {
        new OpenAiCompatibleModelProvider("local", baseUrl, Optional.empty())
                .complete(ModelRequest.of("m", "", List.of(ChatMessage.user("hi")), 32));
        assertTrue(lastBody.get().contains("\"content\":\"hi\""), lastBody.get());
    }
}
