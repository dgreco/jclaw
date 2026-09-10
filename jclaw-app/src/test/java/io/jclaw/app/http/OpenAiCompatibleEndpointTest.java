// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.http;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.storage.approval.JsonlApprovalStore;
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
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OpenAI-compatible endpoint and the browser UI: an OpenAI client gets a completion, buffered
 * or streamed, from a jclaw turn that went through the same runtime as everything else.
 */
@SpringBootTest
class OpenAiCompatibleEndpointTest {

    private static Path workspace;
    private static JclawHttpServer server;
    private static String base;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-oai-it");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("served reply");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired RunStore runs;
    @Autowired EventLog events;
    @Autowired ThreadService threads;
    @Autowired JsonlApprovalStore approvals;
    @Autowired ModelProvider provider;
    @Autowired Clock clock;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private void ensureStarted() throws IOException {
        if (server == null) {
            server = new JclawHttpServer(runtime, runs, events, threads, approvals, clock, Optional.empty(), "mock-model");
            server.start("127.0.0.1", 0);
            base = "http://127.0.0.1:" + server.port();
        }
    }

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String body) {
        return mapper.readValue(body, Map.class);
    }

    @Test
    @DisplayName("a buffered completion has the OpenAI shape and real usage")
    @SuppressWarnings("unchecked")
    void bufferedCompletion() throws Exception {
        ensureStarted();
        HttpResponse<String> response = post("/v1/chat/completions",
                "{\"model\":\"anything\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        assertEquals(200, response.statusCode());
        Map<String, Object> body = json(response.body());
        assertEquals("chat.completion", body.get("object"));
        assertEquals("anything", body.get("model"));
        Map<String, Object> choice = ((List<Map<String, Object>>) body.get("choices")).get(0);
        assertEquals("served reply", ((Map<String, Object>) choice.get("message")).get("content"));
        assertEquals("stop", choice.get("finish_reason"));
        assertEquals(15, ((Number) ((Map<String, Object>) body.get("usage")).get("total_tokens")).intValue(),
                "usage is folded from the run's audited model calls");
        assertTrue(response.headers().firstValue("X-Jclaw-Run").orElse("").startsWith("run_"));
        assertEquals("COMPLETED", response.headers().firstValue("X-Jclaw-Status").orElse(""));
    }

    @Test
    @DisplayName("a streamed completion sends delta chunks and ends with [DONE]")
    void streamedCompletion() throws Exception {
        ensureStarted();
        HttpResponse<String> response = post("/v1/chat/completions",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true}");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertTrue(response.body().contains("\"object\":\"chat.completion.chunk\""));
        assertTrue(response.body().contains("\"content\":\"served reply\""));
        assertTrue(response.body().contains("\"finish_reason\":\"stop\""));
        assertTrue(response.body().trim().endsWith("data: [DONE]"));
    }

    @Test
    @DisplayName("a stateless client's prior turns are replayed; a named thread keeps its own memory")
    void statelessAndStateful() throws Exception {
        ensureStarted();
        MockModelProvider mock = (MockModelProvider) provider;

        HttpResponse<String> stateless = post("/v1/chat/completions",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"first\"},{\"role\":\"assistant\",\"content\":\"one\"},"
                        + "{\"role\":\"user\",\"content\":\"second\"}]}");
        assertEquals(200, stateless.statusCode());
        List<String> seen = mock.lastRequest().orElseThrow().messages().stream().map(ChatMessage::displayText).toList();
        assertEquals(List.of("first", "one", "second"), seen, "the agent saw the conversation the client sent");
        String fresh = stateless.headers().firstValue("X-Jclaw-Thread").orElseThrow();
        assertTrue(fresh.startsWith("oai-"));

        post("/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}", "X-Jclaw-Thread", "oai-named");
        post("/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":\"b\"}]}", "X-Jclaw-Thread", "oai-named");
        assertEquals(4, threads.history(new ThreadId("oai-named"), 10).size(),
                "two turns and two replies on the named thread");
        assertEquals(List.of("a", "served reply", "b"),
                mock.lastRequest().orElseThrow().messages().stream().map(ChatMessage::displayText).toList());
    }

    @Test
    @DisplayName("models are listed, the UI is served, and a body without a user message is a 400")
    void modelsUiAndValidation() throws Exception {
        ensureStarted();
        HttpResponse<String> models = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/models")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(models.body().contains("\"id\":\"mock-model\""));

        HttpResponse<String> ui = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ui.statusCode());
        assertTrue(ui.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(ui.body().contains("<title>jclaw</title>"));

        assertEquals(400, post("/v1/chat/completions", "{\"messages\":[{\"role\":\"system\",\"content\":\"x\"}]}").statusCode());
    }
}
