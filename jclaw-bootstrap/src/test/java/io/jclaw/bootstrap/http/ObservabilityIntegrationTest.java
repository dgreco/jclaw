// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.http;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.bootstrap.observability.Telemetry;
import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.ports.event.EventLog;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.thread.ThreadService;
import io.jclaw.ports.turn.RunStore;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnStatus;
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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A finished run shows up as Prometheus metrics, as an OTLP trace on the HTTP surface, and as a
 * POST to the configured collector.
 */
@SpringBootTest
class ObservabilityIntegrationTest {

    private static Path workspace;
    private static HttpServer collector;
    private static final List<String> received = new CopyOnWriteArrayList<>();
    private static JclawHttpServer server;

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-otel-it");
        collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.start();
    }

    @AfterAll
    static void stop() {
        collector.stop(0);
        if (server != null) {
            server.stop();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.otlp-endpoint", () -> "http://127.0.0.1:" + collector.getAddress().getPort());
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("observed");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired RunStore runs;
    @Autowired EventLog events;
    @Autowired ThreadService threads;
    @Autowired JsonlApprovalStore approvals;
    @Autowired Clock clock;
    @Autowired Telemetry telemetry;

    @Test
    @DisplayName("metrics, trace route, and collector export all reflect the run")
    void observes() throws Exception {
        JclawRuntime.TurnResult result = runtime.submit(new ThreadId("obs"), ChatMessage.user("hi"),
                new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, result.status());

        String metrics = telemetry.prometheus();
        assertTrue(metrics.contains("jclaw_runs_finished_total{status=\"completed\"} 1"), metrics);
        assertTrue(metrics.contains("jclaw_model_calls_total{provider=\"mock\",model=\"mock-model\"} 1"), metrics);
        assertTrue(metrics.contains("jclaw_model_latency_millis_count{provider=\"mock\"} 1"), metrics);

        server = new JclawHttpServer(runtime, runs, events, threads, approvals, clock, Optional.empty(),
                Map.of(), "mock-model", telemetry);
        server.start("127.0.0.1", 0);
        String base = "http://127.0.0.1:" + server.port();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> scraped = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, scraped.statusCode());
        assertTrue(scraped.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertTrue(scraped.body().contains("jclaw_runs_finished_total"));

        HttpResponse<String> trace = client.send(HttpRequest.newBuilder(
                URI.create(base + "/runs/" + result.run().value() + "/trace")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, trace.statusCode());
        assertTrue(trace.body().contains("\"resourceSpans\"") && trace.body().contains("\"name\":\"model.call\""), trace.body());
        assertTrue(trace.body().contains(result.run().value()));

        long deadline = System.currentTimeMillis() + 5000;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, received.size(), "the collector received one export for the finished run");
        assertTrue(received.get(0).contains(result.run().value()) && received.get(0).contains("service.name"));
    }
}
