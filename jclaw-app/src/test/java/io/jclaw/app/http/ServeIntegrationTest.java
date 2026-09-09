package io.jclaw.app.http;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.app.runtime.TurnRunScheduler;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.storage.approval.JsonlApprovalStore;
import io.jclaw.providers.mock.MockModelProvider;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP surface end to end: enqueue over HTTP, execute with the scheduler, read the projection,
 * follow the event stream, read the transcript, and be refused without the token.
 */
@SpringBootTest
class ServeIntegrationTest {

    private static Path workspace;
    private static JclawHttpServer server;
    private static String base;
    private static final String TOKEN = "s3cret";

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-serve-it");
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
    @Autowired TurnRunScheduler scheduler;
    @Autowired Clock clock;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private void ensureStarted() throws IOException {
        if (server == null) {
            server = new JclawHttpServer(runtime, runs, events, threads, approvals, clock, Optional.of(TOKEN));
            server.start("127.0.0.1", 0);
            base = "http://127.0.0.1:" + server.port();
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + TOKEN);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> response) {
        return mapper.readValue(response.body(), Map.class);
    }

    @Test
    @DisplayName("a turn posted over HTTP is queued, executed, projected, streamed, and transcribed")
    void roundTrip() throws Exception {
        ensureStarted();

        HttpResponse<String> accepted = client.send(request("/threads/web-1/turns")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hello over http\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode());
        String run = String.valueOf(json(accepted).get("run"));
        assertTrue(run.startsWith("run_"));

        HttpResponse<String> queued = client.send(request("/runs/" + run).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("QUEUED", json(queued).get("status"), "nothing executes in the request thread");

        scheduler.runOnce(2, new AtomicBoolean(false));

        Map<String, Object> view = json(client.send(request("/runs/" + run).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        assertEquals("COMPLETED", view.get("status"));
        assertEquals("served reply", view.get("reply"));
        assertEquals(1, ((Number) view.get("modelCalls")).intValue());

        HttpResponse<String> stream = client.send(request("/runs/" + run + "/events").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stream.statusCode());
        assertTrue(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertTrue(stream.body().contains("event: turn.submitted"));
        assertTrue(stream.body().contains("event: run.finished"));
        assertTrue(stream.body().endsWith("event: end\ndata: {}\n\n"), "the stream closes after a terminal run");

        Map<String, Object> messages = json(client.send(request("/threads/web-1/messages").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        assertTrue(messages.get("messages").toString().contains("hello over http"));
        assertTrue(messages.get("messages").toString().contains("served reply"));
    }

    @Test
    @DisplayName("without the token every route is refused; health needs it too")
    void tokenRequired() throws Exception {
        ensureStarted();
        HttpResponse<String> refused = client.send(
                HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, refused.statusCode());

        HttpResponse<String> ok = client.send(request("/health").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        assertEquals(404, client.send(request("/nope").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
