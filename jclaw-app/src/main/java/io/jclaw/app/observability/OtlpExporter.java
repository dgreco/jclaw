// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.observability;

import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.domain.observability.RunTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sends a finished run's trace to an OpenTelemetry collector over OTLP/HTTP JSON.
 *
 * <p>The endpoint is operator configuration ({@code jclaw.otlp-endpoint}), so it bypasses the
 * egress guard the way a provider base URL does. Export is asynchronous on one background thread
 * and best-effort: a collector that is down costs a debug line, never a turn. The payload is the
 * projection of the audit log, so nothing reaches the collector that the log does not hold.
 */
public final class OtlpExporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtlpExporter.class);

    private final URI tracesEndpoint;
    private final String serviceName;
    private final HttpClient client;
    private final ExecutorService executor;
    private final JsonMapper mapper = JsonMapper.builder().build();

    /** @param endpoint the collector's base URL; {@code /v1/traces} is appended */
    public OtlpExporter(String endpoint, String serviceName) {
        Objects.requireNonNull(endpoint, "endpoint");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.tracesEndpoint = URI.create(base + "/v1/traces");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jclaw-otlp");
            thread.setDaemon(true);
            return thread;
        });
    }

    public URI tracesEndpoint() {
        return tracesEndpoint;
    }

    /** Queues the run's trace for export. Returns at once. */
    public void export(TurnRunId run, List<JclawEvent> events) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(events, "events");
        String body = mapper.writeValueAsString(RunTrace.otlp(run, events, serviceName));
        // execute, not submit: send() reports its own failures, so the Future would only be a
        // handle nobody holds — and an ignored Future is how a silent failure gets its silence.
        executor.execute(() -> send(run, body));
    }

    private void send(TurnRunId run, String body) {
        try {
            HttpResponse<Void> response = client.send(HttpRequest.newBuilder(tracesEndpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.discarding());
            log.debug("otlp: exported trace for {} to {} ({})", run.value(), tracesEndpoint, response.statusCode());
        } catch (InterruptedException e) {
            // Restore the flag: swallowing it leaves the pool's thread looking uninterrupted,
            // and shutdown then waits for a thread that was already told to stop.
            Thread.currentThread().interrupt();
            log.debug("otlp: export of {} interrupted", run.value());
        } catch (IOException | RuntimeException e) {
            log.debug("otlp: export of {} to {} failed ({})", run.value(), tracesEndpoint, e.getClass().getSimpleName());
        }
    }

    /** Waits briefly for queued exports, then stops. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
