// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.model.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jclaw.ports.Result;
import io.jclaw.ports.memory.Embedding;
import io.jclaw.ports.model.ModelProvider.ProviderFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The embeddings adapter against a real loopback server: the wire is what matters.
 */
class OpenAiCompatibleEmbeddingProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Map<String, List<String>>> lastHeaders = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);

    private static final String RESPONSE = """
            {"object":"list","model":"text-embedding-3-small",
             "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],
             "usage":{"prompt_tokens":3,"total_tokens":3}}
            """;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastHeaders.set(Map.copyOf(exchange.getRequestHeaders()));
        try (InputStream in = exchange.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] body = (status.get() == 200 ? RESPONSE : "{\"error\":{\"message\":\"nope\"}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static Embedding ok(Result<Embedding, ProviderFailure> result) {
        assertInstanceOf(Result.Ok.class, result, "expected success, got " + result);
        return ((Result.Ok<Embedding, ProviderFailure>) result).value();
    }

    private static ProviderFailure err(Result<Embedding, ProviderFailure> result) {
        assertInstanceOf(Result.Err.class, result, "expected failure, got " + result);
        return ((Result.Err<Embedding, ProviderFailure>) result).error();
    }

    private OpenAiCompatibleEmbeddingProvider provider(Optional<String> key, Optional<String> hint) {
        return new OpenAiCompatibleEmbeddingProvider(
                "test", baseUrl, "text-embedding-3-small", key, hint, Map.of("X-Title", "jclaw"),
                java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1).build());
    }

    @Test
    @DisplayName("posts model and input to /embeddings with the bearer key and decodes the vector")
    void embedsOverTheWire() {
        Result<Embedding, ProviderFailure> result =
                provider(Optional.of("sk-test"), Optional.of("OPENAI_API_KEY")).embed("hello");

        Embedding embedding = ok(result);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, embedding.values(), 1e-6f);
        assertEquals("text-embedding-3-small", embedding.model(),
                "the vector carries the model it came from");

        assertEquals(List.of("Bearer sk-test"), lastHeaders.get().get("Authorization"));
        assertEquals(List.of("jclaw"), lastHeaders.get().get("X-title"));
        assertTrue(lastBody.get().contains("\"model\":\"text-embedding-3-small\""));
        assertTrue(lastBody.get().contains("\"input\":\"hello\""));
        assertNull(lastHeaders.get().get("Upgrade"), "no h2c upgrade on plain http");
    }

    @Test
    @DisplayName("a missing credential fails before any request when a hint is registered")
    void missingCredentialIsAuthFailure() {
        Result<Embedding, ProviderFailure> result =
                provider(Optional.empty(), Optional.of("OPENAI_API_KEY")).embed("hello");

        ProviderFailure failure = err(result);
        assertEquals(ProviderFailure.Kind.AUTH, failure.kind());
        assertTrue(failure.detail().orElseThrow().contains("OPENAI_API_KEY"));
        assertNull(lastBody.get(), "nothing should have reached the server");
    }

    @Test
    @DisplayName("without a hint a missing key sends an unauthenticated request, as local servers expect")
    void noHintMeansOptionalKey() {
        Result<Embedding, ProviderFailure> result =
                provider(Optional.empty(), Optional.empty()).embed("hello");

        ok(result);
        assertNull(lastHeaders.get().get("Authorization"));
    }

    @Test
    @DisplayName("HTTP failures are sanitized to the shared categories")
    void classifiesFailures() {
        status.set(429);
        Result<Embedding, ProviderFailure> result =
                provider(Optional.of("k"), Optional.empty()).embed("hello");

        ProviderFailure failure = err(result);
        assertEquals(ProviderFailure.Kind.RATE_LIMIT, failure.kind());
        assertTrue(failure.retryable());
        assertFalse(failure.detail().orElse("").contains("nope"),
                "a rate-limit body is not surfaced");
    }

    @Test
    @DisplayName("blank input is refused locally")
    void blankInputRefused() {
        Result<Embedding, ProviderFailure> result =
                provider(Optional.of("k"), Optional.empty()).embed("   ");
        assertEquals(ProviderFailure.Kind.INVALID_REQUEST, err(result).kind());
        assertNull(lastBody.get());
    }
}
