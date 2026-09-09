package io.jclaw.app.cli;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.app.http.JclawHttpServer;
import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.app.runtime.RecoveryService;
import io.jclaw.app.runtime.RetentionService;
import io.jclaw.app.runtime.RoutineRunner;
import io.jclaw.app.runtime.TurnRunScheduler;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.storage.approval.JsonlApprovalStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-running service: the HTTP surface plus the worker loop in one process.
 *
 * <p>{@code serve} is {@code worker} with an ingress. Turns arrive over HTTP and are queued; the
 * same scheduler that drains {@code jclaw submit} executes them; routines fire, leases are swept,
 * retention runs hourly. Progress is served as projections and streamed as events.
 *
 * <p>Loopback by default, and a bearer token when {@code jclaw.serve-token} is set. There is no
 * TLS here: put it behind a reverse proxy if it leaves the machine.
 */
@Component
@Command(
        name = "serve",
        description = "Run the HTTP ingress and the worker loop until interrupted.",
        mixinStandardHelpOptions = true,
        footer = {
                "",
                "Routes: GET / (browser UI), POST /v1/chat/completions (OpenAI-compatible), GET /v1/models,",
                "        POST /threads/{thread}/turns, GET /runs/{run}, GET /runs/{run}/events (SSE),",
                "        GET /threads/{thread}/messages, GET /approvals, POST /approvals/{gate}, GET /health",
                "Set jclaw.serve-token (or JCLAW_SERVE_TOKEN) to require 'Authorization: Bearer <token>'."
        })
public class ServeCommand implements Callable<Integer> {

    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Duration SWEEP_EVERY = Duration.ofSeconds(30);
    private static final Duration RETENTION_EVERY = Duration.ofHours(1);

    private final JclawProperties properties;
    private final JclawRuntime runtime;
    private final RunStore runs;
    private final EventLog events;
    private final ThreadService threads;
    private final JsonlApprovalStore approvals;
    private final TurnRunScheduler scheduler;
    private final RoutineRunner routines;
    private final RecoveryService recovery;
    private final RetentionService retention;
    private final io.jclaw.app.observability.Telemetry telemetry;
    private final Clock clock;

    @Option(names = "--host", description = "Interface to bind. Default 127.0.0.1.")
    private String host = "127.0.0.1";

    @Option(names = "--port", description = "Port to listen on. Default 8080; 0 picks a free port.")
    private int port = 8080;

    @Option(names = "--concurrency", description = "Queued runs executed at once. Default 2.")
    private int concurrency = 2;

    @Option(names = "--per-user", description = "Queued runs executed at once per user. Default: the concurrency.")
    private int perUser = 0;

    public ServeCommand(
            JclawProperties properties, JclawRuntime runtime, RunStore runs, EventLog events,
            ThreadService threads, JsonlApprovalStore approvals, TurnRunScheduler scheduler,
            RoutineRunner routines, RecoveryService recovery, RetentionService retention,
            io.jclaw.app.observability.Telemetry telemetry, Clock clock) {
        this.telemetry = telemetry;
        this.properties = properties;
        this.runtime = runtime;
        this.runs = runs;
        this.events = events;
        this.threads = threads;
        this.approvals = approvals;
        this.scheduler = scheduler;
        this.routines = routines;
        this.recovery = recovery;
        this.retention = retention;
        this.clock = clock;
    }

    @Override
    public Integer call() throws IOException {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread shutdown = new Thread(() -> stop.set(true), "jclaw-serve-stop");
        Runtime.getRuntime().addShutdownHook(shutdown);

        JclawHttpServer server = new JclawHttpServer(
                runtime, runs, events, threads, approvals, clock, Optional.ofNullable(properties.serveToken()),
                properties.serveUsers(), properties.model(), telemetry);
        server.start(host, port);
        boolean anyAuth = (properties.serveToken() != null && !properties.serveToken().isBlank())
                || !properties.serveUsers().isEmpty();
        System.out.println("jclaw serving on http://" + host + ":" + server.port()
                + (anyAuth
                        ? " (bearer tokens required; " + properties.serveUsers().size() + " user(s))"
                        : " (no token: loopback only is wise)")
                + ". Ctrl-C to stop.");

        long lastSweep = 0;
        long lastRetention = 0;
        try {
            while (!stop.get()) {
                long now = System.nanoTime();
                if (now - lastSweep > SWEEP_EVERY.toNanos()) {
                    recovery.sweep(false);
                    routines.runDue(stop).forEach(fired ->
                            System.out.printf("routine %s -> %s%n", fired.routine().name(), fired.result().status()));
                    lastSweep = now;
                }
                if (lastRetention == 0 || now - lastRetention > RETENTION_EVERY.toNanos()) {
                    retention.sweep(false);
                    lastRetention = now;
                }
                scheduler.reapFinished().forEach(outcome ->
                        System.out.printf("%s -> %s%n", outcome.run().value(), outcome.result().status()));
                scheduler.tick(Math.max(1, concurrency), perUser > 0 ? perUser : Math.max(1, concurrency), stop);
                try {
                    Thread.sleep(TICK.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            server.stop();
            scheduler.drain();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException alreadyShuttingDown) {
                // Shutdown in progress; nothing to remove.
            }
        }
        System.out.println("jclaw serve stopped.");
        return 0;
    }
}
