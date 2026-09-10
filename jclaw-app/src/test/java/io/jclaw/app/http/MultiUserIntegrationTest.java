// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.http;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.app.runtime.TurnRunScheduler;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.memory.MemoryStore;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.TurnRunId;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two users on one server: the same thread name is two conversations, a run belongs to its
 * tenant, and the operator sees everything.
 */
@SpringBootTest
class MultiUserIntegrationTest {

    private static Path workspace;
    private static JclawHttpServer server;
    private static String base;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-users-it");
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
            return MockModelProvider.alwaysReplying("hi there");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired RunStore runs;
    @Autowired EventLog events;
    @Autowired ThreadService threads;
    @Autowired JsonlApprovalStore approvals;
    @Autowired TurnRunScheduler scheduler;
    @Autowired Clock clock;
    @Autowired MemoryStore memories;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private void ensureStarted() throws IOException {
        if (server == null) {
            server = new JclawHttpServer(runtime, runs, events, threads, approvals, clock,
                    Optional.of("op-token"), Map.of("alice", "tok-alice", "bob", "tok-bob"), "mock-model");
            server.start("127.0.0.1", 0);
            base = "http://127.0.0.1:" + server.port();
        }
    }

    private HttpResponse<String> call(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        request = method.equals("GET") ? request.GET() : request.POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> response) {
        return mapper.readValue(response.body(), Map.class);
    }

    @Test
    @DisplayName("the same thread name is a different conversation per user, and runs are owned")
    void usersAreIsolated() throws Exception {
        ensureStarted();

        String aliceRun = String.valueOf(json(call("tok-alice", "POST", "/threads/work/turns",
                "{\"text\":\"alice here\"}")).get("run"));
        String bobRun = String.valueOf(json(call("tok-bob", "POST", "/threads/work/turns",
                "{\"text\":\"bob here\"}")).get("run"));
        scheduler.runOnce(4, new AtomicBoolean(false));

        assertEquals("alice", runs.find(new TurnRunId(aliceRun)).orElseThrow().scope().tenant(),
                "the run's scope carries the user as its tenant");
        assertEquals("bob", runs.find(new TurnRunId(bobRun)).orElseThrow().scope().tenant());

        String aliceView = call("tok-alice", "GET", "/threads/work/messages", null).body();
        String bobView = call("tok-bob", "GET", "/threads/work/messages", null).body();
        assertTrue(aliceView.contains("alice here") && !aliceView.contains("bob here"));
        assertTrue(bobView.contains("bob here") && !bobView.contains("alice here"));

        assertEquals(404, call("tok-bob", "GET", "/runs/" + aliceRun, null).statusCode(),
                "another user's run does not exist as far as bob can tell");
        assertEquals(200, call("tok-alice", "GET", "/runs/" + aliceRun, null).statusCode());
        assertEquals(200, call("op-token", "GET", "/runs/" + aliceRun, null).statusCode(),
                "the operator reads any tenant's run");
        assertEquals(401, call("tok-nobody", "GET", "/health", null).statusCode());

        // The operator's threads are the CLI's: bare names, the local tenant.
        call("op-token", "POST", "/threads/work/turns", "{\"text\":\"operator here\"}");
        List<String> operatorThread = threads.history(new io.jclaw.contracts.turn.ThreadId("work"), 10).stream()
                .map(m -> m.message().displayText()).toList();
        assertEquals(List.of("operator here"), operatorThread);
        assertFalse(threads.history(new io.jclaw.contracts.turn.ThreadId("alice:work"), 10).isEmpty());

        // Memories, like everything scope-keyed, separate by tenant as well as by project.
        var aliceScope = runs.find(new TurnRunId(aliceRun)).orElseThrow().scope();
        var bobScope = runs.find(new TurnRunId(bobRun)).orElseThrow().scope();
        memories.write(aliceScope, "alice prefers tabs", List.of());
        assertEquals(1, memories.count(aliceScope));
        assertEquals(0, memories.count(bobScope), "bob's project scope holds none of alice's memories");
        assertEquals(0, memories.count(runtime.scopeFor(new io.jclaw.contracts.turn.ThreadId("work"))),
                "nor does the operator's");
    }
}
