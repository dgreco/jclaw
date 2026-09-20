// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.http;

import io.jclaw.bootstrap.observability.Telemetry;
import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.bootstrap.runtime.TurnRunScheduler;
import io.jclaw.ports.event.EventLog;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.routine.RoutineStore;
import io.jclaw.ports.thread.ThreadService;
import io.jclaw.ports.turn.RunStore;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnStatus;
import io.jclaw.domain.trigger.Trigger;
import io.jclaw.adapter.out.model.mock.MockModelProvider;
import io.jclaw.adapter.out.persistence.approval.JsonlApprovalStore;
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

/** Webhook and event triggers enqueue a routine's turn; a routine's own run never re-triggers. */
@SpringBootTest
class TriggerIntegrationTest {

    private static Path workspace;
    private static JclawHttpServer server;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-triggers-it");
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
        registry.add("jclaw.approval-mode", () -> "trusted");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("noted");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired RoutineStore routines;
    @Autowired RunStore runs;
    @Autowired EventLog events;
    @Autowired ThreadService threads;
    @Autowired JsonlApprovalStore approvals;
    @Autowired TurnRunScheduler scheduler;
    @Autowired Telemetry telemetry;
    @Autowired Clock clock;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(String base, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a webhook with the right secret enqueues the routine's turn with the payload")
    void webhook() throws Exception {
        var scope = runtime.scopeFor(new ThreadId("routines"));
        routines.create(scope, "deploy", "webhook sha256:" + Trigger.hashSecret("hooksecret"), "UTC",
                "Summarise this deploy notification.", new ThreadId("deploy-hook"));

        server = new JclawHttpServer(runtime, runs, events, threads, approvals, clock, Optional.of("op-token"),
                Map.of(), "mock-model", telemetry, routines);
        server.start("127.0.0.1", 0);
        String base = "http://127.0.0.1:" + server.port();

        assertEquals(401, post(base, "/hooks/deploy", "wrong", "{}").statusCode());
        assertEquals(401, post(base, "/hooks/deploy", null, "{}").statusCode());
        assertEquals(404, post(base, "/hooks/nothing", "hooksecret", "{}").statusCode());

        HttpResponse<String> accepted = post(base, "/hooks/deploy", "hooksecret", "{\"service\":\"api\",\"version\":\"1.2.3\"}");
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertTrue(accepted.body().contains("\"run\""));

        List<RunStore.RunRecord> queued = runs.byStatus(TurnStatus.QUEUED, 10);
        assertTrue(queued.stream().anyMatch(r -> r.scope().thread().value().equals("deploy-hook")), queued.toString());
        String inbound = threads.history(new ThreadId("deploy-hook"), 10).get(0).message().displayText();
        assertTrue(inbound.contains("Summarise this deploy notification.") && inbound.contains("\"version\":\"1.2.3\""), inbound);
        assertTrue(routines.list(scope).get(0).lastFiredAt().isPresent(), "the firing is recorded");
    }

    @Test
    @DisplayName("an event trigger fires on a matching event, and a routine's own run does not re-trigger it")
    void eventTrigger() throws Exception {
        var scope = runtime.scopeFor(new ThreadId("routines"));
        routines.create(scope, "on-finish", "on run.finished status=COMPLETED", "UTC",
                "A run just finished; note it.", new ThreadId("reactor"));

        JclawRuntime.TurnResult result = runtime.submit(new ThreadId("work"), ChatMessage.user("hello"),
                new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, result.status());

        List<RunStore.RunRecord> queued = runs.byStatus(TurnStatus.QUEUED, 10).stream()
                .filter(r -> r.scope().thread().value().equals("reactor")).toList();
        assertEquals(1, queued.size(), "the finished run enqueued the reactor's turn");
        String inbound = threads.history(new ThreadId("reactor"), 10).get(0).message().displayText();
        assertTrue(inbound.contains("Triggering event: run.finished") && inbound.contains("status=COMPLETED")
                && inbound.contains("thread=work"), inbound);

        scheduler.runOnce(2, new AtomicBoolean(false));
        assertEquals(TurnStatus.COMPLETED, runs.find(queued.get(0).run()).orElseThrow().status());
        long reactorRuns = runs.byStatus(TurnStatus.QUEUED, 10).stream()
                .filter(r -> r.scope().thread().value().equals("reactor")).count();
        assertEquals(0, reactorRuns, "the reactor's own run finishing did not fire it again");
    }

    @Test
    @DisplayName("one authenticated POST fans out to every routine sharing the topic")
    void webhookFanOut() throws Exception {
        var scope = runtime.scopeFor(new ThreadId("routines"));
        routines.create(scope, "primary", "webhook sha256:" + Trigger.hashSecret("topicsecret")
                + " topic=deploys", "UTC", "Handle the deploy.", new ThreadId("fan-primary"));
        // A different secret, the same topic: declaring the topic is the subscription, and it is
        // written by the operator in the routine, not by the caller in the request.
        routines.create(scope, "listener", "webhook sha256:" + Trigger.hashSecret("someone-elses")
                + " topic=deploys", "UTC", "Note the deploy.", new ThreadId("fan-listener"));
        routines.create(scope, "unrelated", "webhook sha256:" + Trigger.hashSecret("third")
                + " topic=releases", "UTC", "Not this one.", new ThreadId("fan-unrelated"));
        routines.create(scope, "topicless", "webhook sha256:" + Trigger.hashSecret("fourth"),
                "UTC", "Nor this one.", new ThreadId("fan-topicless"));

        server = new JclawHttpServer(runtime, runs, events, threads, approvals, clock, Optional.of("op-token"),
                Map.of(), "mock-model", telemetry, routines);
        server.start("127.0.0.1", 0);
        String base = "http://127.0.0.1:" + server.port();

        HttpResponse<String> accepted = post(base, "/hooks/primary", "topicsecret", "{\"v\":\"9\"}");
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertTrue(accepted.body().contains("\"topic\":\"deploys\""), accepted.body());
        assertTrue(accepted.body().contains("listener"), accepted.body());

        List<String> threadsQueued = runs.byStatus(TurnStatus.QUEUED, 20).stream()
                .map(r -> r.scope().thread().value()).toList();
        assertTrue(threadsQueued.contains("fan-primary"), threadsQueued.toString());
        assertTrue(threadsQueued.contains("fan-listener"),
                "the subscriber ran without presenting its own secret: " + threadsQueued);
        assertFalse(threadsQueued.contains("fan-unrelated"), "a different topic is not a subscription");
        assertFalse(threadsQueued.contains("fan-topicless"),
                "a blank topic subscribes to nothing, so two of them are not a fan-out group");

        String listened = threads.history(new ThreadId("fan-listener"), 10).get(0).message().displayText();
        assertTrue(listened.contains("Note the deploy.") && listened.contains("topic deploys")
                && listened.contains("\"v\":\"9\""), listened);

        // The subscriber's own endpoint still needs its own secret: fan-out adds a way in for the
        // operator, not for a caller.
        assertEquals(401, post(base, "/hooks/listener", "topicsecret", "{}").statusCode());
    }

    @Test
    @DisplayName("an inbound turn on a watched thread fires a routine, and the routine's own does not")
    void inboundMessageTrigger() {
        var scope = runtime.scopeFor(new ThreadId("routines"));
        routines.create(scope, "triage", "on turn.submitted thread=inbox", "UTC",
                "Something arrived; triage it.", new ThreadId("triager"));

        runtime.enqueue(new ThreadId("inbox"), io.jclaw.ports.model.ChatMessage.user("a message"));

        List<String> queued = runs.byStatus(TurnStatus.QUEUED, 20).stream()
                .map(r -> r.scope().thread().value()).toList();
        assertTrue(queued.contains("triager"),
                "a turn arriving from anywhere is the inbound-message trigger: " + queued);

        // The routine's own enqueue also emitted turn.submitted. If that could trigger, this
        // would be a loop rather than a routine.
        long triagerRuns = runs.byStatus(TurnStatus.QUEUED, 50).stream()
                .filter(r -> r.scope().thread().value().equals("triager")).count();
        assertEquals(1, triagerRuns, "a routine's own turn must not fire it again");
    }
}
