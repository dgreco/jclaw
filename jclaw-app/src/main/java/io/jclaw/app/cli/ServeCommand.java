// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.app.channel.ChannelService;
import io.jclaw.app.config.JclawProperties;
import io.jclaw.app.http.JclawHttpServer;
import io.jclaw.app.identity.LoginProvider;
import io.jclaw.app.identity.OidcLogin;
import io.jclaw.app.observability.Telemetry;
import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.app.runtime.RecoveryService;
import io.jclaw.app.runtime.RetentionService;
import io.jclaw.app.runtime.RoutineRunner;
import io.jclaw.app.runtime.TurnRunScheduler;
import io.jclaw.app.runtime.WatchTriggerScanner;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.identity.SessionStore;
import io.jclaw.contracts.inbound.InboundReviewStore;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.domain.safety.InboundPolicy;
import io.jclaw.domain.secret.SecretInjection;
import io.jclaw.storage.approval.JsonlApprovalStore;
import io.jclaw.storage.projection.RunProjectionCache;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    private final WatchTriggerScanner watches;
    private final RecoveryService recovery;
    private final RetentionService retention;
    private final Telemetry telemetry;
    private final RoutineStore routineStore;
    private final ChannelService channelService;
    private final SessionStore sessionStore;
    private final LoginProvider oidcLogin;
    private final SecretVault vault;
    private final RunProjectionCache projections;
    private final InboundReviewStore inboundReview;
    private final Clock clock;

    @Option(names = "--host", description = "Interface to bind. Default 127.0.0.1.")
    private String host = "127.0.0.1";

    @Option(names = "--port", description = "Port to listen on. Default 8080; 0 picks a free port.")
    private int port = 8080;

    @Option(names = "--concurrency", description = "Queued runs executed at once. Default 2.")
    private int concurrency = 2;

    @Option(names = "--per-user", description = "Queued runs executed at once per user. Default: the concurrency.")
    private int perUser;

    public ServeCommand(
            JclawProperties properties, JclawRuntime runtime, RunStore runs, EventLog events,
            ThreadService threads, JsonlApprovalStore approvals, TurnRunScheduler scheduler,
            RoutineRunner routines, WatchTriggerScanner watches,
            RecoveryService recovery, RetentionService retention,
            Telemetry telemetry,
            RoutineStore routineStore,
            ChannelService channelService,
            SessionStore sessionStore,
            LoginProvider oidcLogin,
            SecretVault vault,
            RunProjectionCache projections,
            InboundReviewStore inboundReview, Clock clock) {
        this.channelService = channelService;
        this.sessionStore = sessionStore;
        this.oidcLogin = oidcLogin;
        this.vault = vault;
        this.projections = projections;
        this.inboundReview = inboundReview;
        this.telemetry = telemetry;
        this.routineStore = routineStore;
        this.properties = properties;
        this.runtime = runtime;
        this.runs = runs;
        this.events = events;
        this.threads = threads;
        this.approvals = approvals;
        this.scheduler = scheduler;
        this.routines = routines;
        this.watches = watches;
        this.recovery = recovery;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * Leases the OIDC client secret at the moment of exchange, never at start.
     *
     * <p>The binding names {@code identity.login} and the provider's host, so a secret meant for
     * a tool cannot be spent signing people in, and one meant for another provider cannot be sent
     * to this one.
     */
    private Optional<String> leaseOidcSecret() {
        if (!properties.oidcConfigured()) {
            return Optional.empty();
        }
        try {
            var name = new SecretVault.SecretName(properties.oidcClientSecret());
            var lease = vault.lease(name);
            if (lease.isEmpty()) {
                return Optional.empty();
            }
            String host = URI.create(properties.oidcIssuer()).getHost();
            var refusal = SecretInjection.refuse(
                    lease.get().info().binding(), OidcLogin.LOGIN,
                    Set.of(host == null ? "" : host));
            return refusal.isPresent() ? Optional.empty()
                    : Optional.of(lease.get().value());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public Integer call() throws IOException {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread shutdown = new Thread(() -> stop.set(true), "jclaw-serve-stop");
        Runtime.getRuntime().addShutdownHook(shutdown);

        JclawHttpServer server = new JclawHttpServer(
                runtime, runs, events, threads, approvals, clock, Optional.ofNullable(properties.serveToken()),
                properties.serveUsers(), properties.model(), telemetry, routineStore);
        server.withIdentity(sessionStore, properties.roles(), oidcLogin.orNull(),
                () -> leaseOidcSecret(), properties.oidcRedirectUri());
        server.withChannels(channelService);
        server.withProjectionCache(projections);
        server.withInboundScreening(
                InboundPolicy.parse(properties.inboundPolicy()), inboundReview);
        server.start(host, port);
        boolean anyAuth = (properties.serveToken() != null && !properties.serveToken().isBlank())
                || !properties.serveUsers().isEmpty();
        System.out.println("jclaw serving on http://" + host + ":" + server.port()
                + (anyAuth
                        ? " (bearer tokens required; " + properties.serveUsers().size() + " user(s))"
                        : " (no token: loopback only is wise)")
                + (channelService.enabled()
                        ? ", channels " + String.join(", ", new TreeSet<>(channelService.channels()))
                        : "")
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
                    watches.scan().forEach(watched -> System.out.printf(
                            "routine %s -> queued %s (watch %s)%n",
                            watched.routine().name(), watched.run().value(), watched.glob()));
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
