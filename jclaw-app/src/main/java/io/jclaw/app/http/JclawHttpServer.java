package io.jclaw.app.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jclaw.app.observability.Telemetry;
import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.domain.observability.RunTrace;
import io.jclaw.domain.trigger.Trigger;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.identity.Role;
import io.jclaw.contracts.identity.SessionStore;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.GateId;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.projection.RunProjection;
import io.jclaw.storage.approval.JsonlApprovalStore;
import io.jclaw.storage.event.EventCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The second product surface: an HTTP ingress over the same runtime the CLI uses.
 *
 * <p>Same shape as IronClaw's host ingress in miniature. Inbound turns are <em>enqueued</em>,
 * never executed in the request thread, and the caller gets a run id at once; a scheduler in the
 * same process executes them. Progress is a projection folded from the event log, and a live
 * stream is the same log followed as server-sent events. Nothing here reaches into the
 * interpreter or a store's internals: every route is a call the CLI could make too.
 *
 * <p>Two more surfaces sit on the same routes. {@code GET /} serves a small browser UI
 * ({@link WebUi}) that uses nothing but them. {@code POST /v1/chat/completions} speaks the OpenAI
 * chat-completions shape, buffered or streamed, so any OpenAI SDK or tool can drive the agent;
 * unlike the enqueue route it executes in the request, because that is what such clients expect,
 * and it still goes through the same lock, lease, and kernel as a CLI turn.
 *
 * <p>Bound to loopback unless told otherwise, and when a token is configured every request must
 * carry it, as a bearer header or, for browsers' {@code EventSource}, an {@code access_token}
 * query parameter. Built on the JDK's own HTTP server: one dependency fewer, and it works in the
 * native image.
 */
public final class JclawHttpServer {

    private static final Logger log = LoggerFactory.getLogger(JclawHttpServer.class);

    private static final Pattern THREAD_TURNS = Pattern.compile("^/threads/([^/]+)/turns$");
    private static final Pattern THREAD_MESSAGES = Pattern.compile("^/threads/([^/]+)/messages$");
    private static final Pattern RUN = Pattern.compile("^/runs/([^/]+)$");
    private static final Pattern RUN_EVENTS = Pattern.compile("^/runs/([^/]+)/events$");
    private static final Pattern RUN_TRACE = Pattern.compile("^/runs/([^/]+)/trace$");
    private static final Pattern APPROVAL = Pattern.compile("^/approvals/([^/]+)$");
    private static final Pattern HOOK = Pattern.compile("^/hooks/([^/]+)$");
    private static final Pattern CHANNEL = Pattern.compile("^/channels/([^/]+)$");
    private static final int MAX_CHANNEL_BODY = 256 * 1024;
    private static final int MAX_WEBHOOK_BODY = 16 * 1024;

    /** How often the event stream polls the log. */
    private static final long STREAM_POLL_MILLIS = 250;

    private final JclawRuntime runtime;
    private final RunStore runs;
    private final EventLog events;
    private io.jclaw.storage.projection.RunProjectionCache projections =
            io.jclaw.storage.projection.RunProjectionCache.none();
    private final ThreadService threads;
    private final JsonlApprovalStore approvals;
    private final Clock clock;
    private final Optional<String> token;
    private final Map<String, String> tokensToUsers;
    private final String model;
    private final Telemetry telemetry;
    private final Optional<RoutineStore> routines;
    private Optional<io.jclaw.app.channel.ChannelService> channels = Optional.empty();
    private Optional<SessionStore> sessions = Optional.empty();
    private Optional<io.jclaw.app.identity.OidcLogin> oidc = Optional.empty();
    private java.util.function.Supplier<Optional<String>> oidcClientSecret = Optional::empty;
    private Map<String, Role> userRoles = Map.of();
    private String loginRedirectUri = "";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;

    public JclawHttpServer(
            JclawRuntime runtime, RunStore runs, EventLog events, ThreadService threads,
            JsonlApprovalStore approvals, Clock clock, Optional<String> token, String model) {
        this(runtime, runs, events, threads, approvals, clock, token, Map.of(), model);
    }

    /**
     * @param token operator token; the operator is the {@code local} tenant and reads everything
     * @param users user name to bearer token; each user is a tenant
     */
    public JclawHttpServer(
            JclawRuntime runtime, RunStore runs, EventLog events, ThreadService threads,
            JsonlApprovalStore approvals, Clock clock, Optional<String> token,
            Map<String, String> users, String model) {
        this(runtime, runs, events, threads, approvals, clock, token, users, model, new Telemetry());
    }

    /** @param telemetry the process metrics {@code /metrics} renders */
    public JclawHttpServer(
            JclawRuntime runtime, RunStore runs, EventLog events, ThreadService threads,
            JsonlApprovalStore approvals, Clock clock, Optional<String> token,
            Map<String, String> users, String model, Telemetry telemetry) {
        this(runtime, runs, events, threads, approvals, clock, token, users, model, telemetry, null);
    }

    /** @param routines the routine store, so {@code POST /hooks/{name}} can fire webhook routines */
    public JclawHttpServer(
            JclawRuntime runtime, RunStore runs, EventLog events, ThreadService threads,
            JsonlApprovalStore approvals, Clock clock, Optional<String> token,
            Map<String, String> users, String model, Telemetry telemetry, RoutineStore routines) {
        this.routines = Optional.ofNullable(routines);
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.events = Objects.requireNonNull(events, "events");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.token = Objects.requireNonNull(token, "token").filter(t -> !t.isBlank());
        Map<String, String> byToken = new LinkedHashMap<>();
        Objects.requireNonNull(users, "users").forEach((name, secret) -> {
            new Principal(name, io.jclaw.contracts.identity.Role.MEMBER); // validates the name
            if (secret != null && !secret.isBlank()) {
                byToken.put(secret, name);
            }
        });
        this.tokensToUsers = Map.copyOf(byToken);
        this.model = Objects.requireNonNull(model, "model");
    }

    /** Starts listening. Port 0 picks a free port; {@link #port()} reports it. */
    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "jclaw-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        log.debug("http: listening on {}:{}", host, port());
    }

    /**
     * Serves the login routes and accepts session tokens.
     *
     * @param roles           role per configured static user; anyone unlisted is a member
     * @param oidc            the provider, when one is configured
     * @param clientSecret    leases the OIDC client secret at the moment of exchange
     * @param redirectUri     the callback this server is reachable at, as registered with the
     *                        provider. It is sent in both legs of the flow and must match
     */
    public void withIdentity(SessionStore sessionStore, Map<String, Role> roles,
            io.jclaw.app.identity.OidcLogin oidc,
            java.util.function.Supplier<Optional<String>> clientSecret, String redirectUri) {
        this.sessions = Optional.ofNullable(sessionStore);
        this.userRoles = Map.copyOf(Objects.requireNonNull(roles, "roles"));
        this.oidc = Optional.ofNullable(oidc);
        this.oidcClientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.loginRedirectUri = Objects.requireNonNull(redirectUri, "redirectUri");
    }

    /**
     * Uses materialised projections where the storage backend keeps them.
     *
     * <p>Optional, and defaulted to none rather than required, because a projection cache is an
     * optimisation: a server wired without one answers identically, only slower on long runs.
     */
    public void withProjectionCache(io.jclaw.storage.projection.RunProjectionCache cache) {
        this.projections = Objects.requireNonNull(cache, "cache");
    }

    /** Serves {@code POST /channels/{adapter}}. Optional: without it the route is a 404. */
    public void withChannels(io.jclaw.app.channel.ChannelService channelService) {
        this.channels = Optional.ofNullable(channelService);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // --- routing ---

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            String route = exchange.getRequestURI().getPath();
            if (oidc.isPresent() && exchange.getRequestMethod().equals("GET") && route.equals("/login/oidc")) {
                beginLogin(exchange);
                return;
            }
            if (oidc.isPresent() && exchange.getRequestMethod().equals("GET") && route.equals("/login/callback")) {
                finishLogin(exchange);
                return;
            }
            Matcher channel = CHANNEL.matcher(exchange.getRequestURI().getPath());
            if (exchange.getRequestMethod().equals("POST") && channel.matches()) {
                // A platform authenticates with its own signature, not the operator's token.
                channel(exchange, channel.group(1));
                return;
            }
            Matcher hook = HOOK.matcher(exchange.getRequestURI().getPath());
            if (exchange.getRequestMethod().equals("POST") && hook.matches()) {
                // A webhook authenticates with its own secret, never with the operator's token:
                // the caller is an outside system that must be able to reach exactly one routine.
                webhook(exchange, hook.group(1));
                return;
            }
            Optional<Principal> who = authenticate(exchange);
            if (who.isEmpty()) {
                send(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            Principal principal = who.get();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Matcher m;

            if (method.equals("GET") && path.equals("/health")) {
                send(exchange, 200, Map.of("status", "ok", "at", clock.instant().toString()));
            } else if (method.equals("GET") && (path.equals("/") || path.equals("/ui"))) {
                sendHtml(exchange, WebUi.INDEX);
            } else if (method.equals("GET") && path.equals("/v1/models")) {
                send(exchange, 200, Map.of("object", "list", "data", List.of(Map.of(
                        "id", model, "object", "model", "owned_by", "jclaw"))));
            } else if (method.equals("POST") && path.equals("/v1/chat/completions")) {
                if (!principal.mayWrite()) {
                    send(exchange, 403, Map.of("error", "a viewer may not start a turn"));
                } else {
                    chatCompletions(exchange, principal);
                }
            } else if (method.equals("POST") && (m = THREAD_TURNS.matcher(path)).matches()) {
                if (!principal.mayWrite()) {
                    send(exchange, 403, Map.of("error", "a viewer may not start a turn"));
                } else {
                    submitTurn(exchange, principal, principal.thread(m.group(1)));
                }
            } else if (method.equals("GET") && (m = THREAD_MESSAGES.matcher(path)).matches()) {
                send(exchange, 200, Map.of("thread", m.group(1), "messages",
                        threads.history(principal.thread(m.group(1)), Integer.MAX_VALUE).stream()
                                .map(this::message).toList()));
            } else if (method.equals("GET") && (m = RUN.matcher(path)).matches()) {
                run(exchange, principal, new TurnRunId(m.group(1)));
            } else if (method.equals("POST") && path.equals("/login")) {
                mintSession(exchange, principal);
            } else if (method.equals("POST") && path.equals("/logout")) {
                send(exchange, 200, Map.of("revoked", revokePresented(exchange)));
            } else if (method.equals("GET") && path.equals("/sessions")) {
                send(exchange, principal.role().canAdminister() ? 200 : 403,
                        principal.role().canAdminister()
                                ? Map.of("sessions", sessions.map(SessionStore::active).orElse(List.of()).stream()
                                        .map(session -> Map.of("user", session.user(),
                                                "role", session.role().name(),
                                                "expiresAt", session.expiresAt().toString())).toList())
                                : Map.of("error", "forbidden"));
            } else if (method.equals("GET") && path.equals("/metrics")) {
                sendText(exchange, "text/plain; version=0.0.4; charset=utf-8", telemetry.prometheus());
            } else if (method.equals("GET") && (m = RUN_TRACE.matcher(path)).matches()) {
                TurnRunId run = new TurnRunId(m.group(1));
                if (!owned(principal, run)) {
                    send(exchange, 404, Map.of("error", "no such run"));
                } else {
                    send(exchange, 200, RunTrace.otlp(run,
                            events.readRun(run).stream().map(EventLog.Entry::event).toList(), "jclaw"));
                }
            } else if (method.equals("GET") && (m = RUN_EVENTS.matcher(path)).matches()) {
                TurnRunId run = new TurnRunId(m.group(1));
                if (!owned(principal, run)) {
                    send(exchange, 404, Map.of("error", "no such run"));
                } else {
                    stream(exchange, run);
                }
            } else if (method.equals("GET") && path.equals("/approvals")) {
                send(exchange, 200, Map.of("gates", approvals.allPending().stream()
                        .filter(gate -> principal.mayRead(gate.scope()))
                        .map(this::gate).toList()));
            } else if (method.equals("POST") && (m = APPROVAL.matcher(path)).matches()) {
                if (!principal.mayWrite()) {
                    send(exchange, 403, Map.of("error", "a viewer may not answer a gate"));
                } else {
                    decide(exchange, principal, new GateId(m.group(1)));
                }
            } else {
                send(exchange, 404, Map.of("error", "no such route"));
            }
        } catch (IllegalArgumentException e) {
            send(exchange, 400, Map.of("error", "bad request"));
        } catch (RuntimeException e) {
            log.debug("http: request failed ({})", e.getClass().getSimpleName());
            send(exchange, 500, Map.of("error", "internal"));
        }
    }

    /**
     * Who is calling: the operator (by {@code serve-token}, or unauthenticated when no auth is
     * configured), a named user by their token, or nobody.
     */
    private Optional<Principal> authenticate(HttpExchange exchange) {
        if (token.isEmpty() && tokensToUsers.isEmpty() && sessions.isEmpty()) {
            return Optional.of(Principal.operator());
        }
        // EventSource cannot set headers; the browser UI passes the token as a query parameter.
        String presented = presentedToken(exchange).orElse(null);
        if (presented == null) {
            return Optional.empty();
        }
        if (token.isPresent() && token.get().equals(presented)) {
            return Optional.of(Principal.operator());
        }
        String user = tokensToUsers.get(presented);
        if (user != null) {
            return Optional.of(new Principal(user, userRoles.getOrDefault(user, Role.MEMBER)));
        }
        // A session, which unlike a static token names a person, carries a role, and expires.
        return sessions.flatMap(store -> store.find(presented))
                .map(session -> session.role() == Role.OPERATOR
                        ? Principal.operator()
                        : new Principal(session.user(), session.role()));
    }

    /** Whatever bearer the caller presented, however they presented it. */
    private Optional<String> presentedToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return Optional.of(header.substring("Bearer ".length()).trim());
        }
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals("access_token")) {
                    return Optional.of(java.net.URLDecoder.decode(
                            pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return Optional.empty();
    }

    /** Mints a session for someone else. Operators only: this is handing out access. */
    private void mintSession(HttpExchange exchange, Principal principal) throws IOException {
        if (!principal.role().canAdminister() || sessions.isEmpty()) {
            send(exchange, 403, Map.of("error", "forbidden"));
            return;
        }
        Map<String, Object> body = readJson(exchange);
        String user = body.get("user") instanceof String name ? name.trim() : "";
        if (user.isEmpty()) {
            send(exchange, 400, Map.of("error", "user required"));
            return;
        }
        Role role;
        try {
            role = Role.valueOf(String.valueOf(body.getOrDefault("role", "MEMBER"))
                    .toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, Map.of("error", "role must be VIEWER, MEMBER, or OPERATOR"));
            return;
        }
        java.time.Duration ttl;
        try {
            ttl = java.time.Duration.parse(String.valueOf(body.getOrDefault("ttl", "PT12H")));
        } catch (RuntimeException e) {
            send(exchange, 400, Map.of("error", "ttl must be an ISO duration"));
            return;
        }
        SessionStore.Session issued = sessions.get().issue(user, role, ttl);
        send(exchange, 201, Map.of(
                "token", issued.token().orElseThrow(),
                "user", issued.user(),
                "role", issued.role().name(),
                "expiresAt", issued.expiresAt().toString()));
    }

    private boolean revokePresented(HttpExchange exchange) {
        return sessions.isPresent()
                && presentedToken(exchange).map(sessions.get()::revoke).orElse(false);
    }

    /** Sends the browser to the provider. */
    private void beginLogin(HttpExchange exchange) throws IOException {
        var started = oidc.get().start(loginRedirectUri);
        if (started.isErr()) {
            send(exchange, 502, Map.of("error", started.errorAsOptional().orElse("login unavailable")));
            return;
        }
        exchange.getResponseHeaders().add("Location", started.orElseThrow());
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /** Exchanges the code the provider sent back and issues a session. */
    private void finishLogin(HttpExchange exchange) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(pair.substring(0, eq),
                            java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        if (query.containsKey("error")) {
            send(exchange, 401, Map.of("error", "login refused"));
            return;
        }
        String code = query.get("code");
        String state = query.get("state");
        if (code == null || state == null) {
            send(exchange, 400, Map.of("error", "code and state required"));
            return;
        }
        Optional<String> secret = oidcClientSecret.get();
        if (secret.isEmpty() || sessions.isEmpty()) {
            send(exchange, 502, Map.of("error", "login unavailable"));
            return;
        }
        var identity = oidc.get().complete(code, state, secret.get());
        if (identity.isErr()) {
            log.debug("login: exchange failed ({})", identity.errorAsOptional().orElse("?"));
            send(exchange, 401, Map.of("error", "login failed"));
            return;
        }
        String user = identity.orElseThrow().user();
        SessionStore.Session issued = sessions.get().issue(
                user, userRoles.getOrDefault(user, Role.MEMBER), java.time.Duration.ofHours(12));
        send(exchange, 200, Map.of(
                "token", issued.token().orElseThrow(),
                "user", issued.user(),
                "role", issued.role().name(),
                "expiresAt", issued.expiresAt().toString()));
    }

    private boolean owned(Principal principal, TurnRunId run) {
        return runs.find(run).map(record -> principal.mayRead(record.scope())).orElse(false);
    }

    /**
     * {@code POST /v1/chat/completions}: the OpenAI shape over a jclaw turn.
     *
     * <p>Executes in the request, unlike the enqueue route, because OpenAI clients wait for the
     * completion. A stateless client's prior turns are replayed into a fresh thread first so the
     * agent sees the conversation it sent; a client naming {@code X-Jclaw-Thread} relies on
     * jclaw's own transcript instead. A parked run is reported as a completion whose content
     * says what it is waiting for, with the run and gate ids in response headers, since the
     * OpenAI shape has no notion of a gate.
     */
    private void chatCompletions(HttpExchange exchange, Principal principal) throws IOException {
        OpenAiCompat.Parsed parsed = OpenAiCompat.parse(readJson(exchange));
        String named = exchange.getRequestHeaders().getFirst("X-Jclaw-Thread");
        ThreadId thread;
        if (named != null && !named.isBlank()) {
            thread = principal.thread(named);
        } else {
            thread = principal.thread("oai-" + java.util.UUID.randomUUID().toString().substring(0, 8));
            for (ChatMessage prior : parsed.priorTurns()) {
                if (prior.role() == ChatMessage.Role.USER) {
                    threads.acceptInbound(thread, prior);
                } else {
                    threads.appendAssistant(thread, prior, false);
                }
            }
        }
        long created = clock.instant().getEpochSecond();
        String requestedModel = parsed.model().orElse(model);

        if (!parsed.stream()) {
            JclawRuntime.TurnResult result = runtime.submit(principal.tenant(),
                    thread, parsed.inbound(), new java.util.concurrent.atomic.AtomicBoolean(false), Optional.empty());
            respondCompletion(exchange, result, thread, created, requestedModel);
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("X-Jclaw-Thread", thread.value());
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            String id = "chatcmpl-" + thread.value();
            java.util.function.Consumer<ModelProvider.StreamEvent> sink = event -> {
                if (event instanceof ModelProvider.StreamEvent.TextDelta delta) {
                    try {
                        writeSse(out, mapper.writeValueAsString(
                                OpenAiCompat.chunk(id, created, requestedModel, delta.text(), null)));
                    } catch (IOException clientGone) {
                        throw new java.io.UncheckedIOException(clientGone);
                    }
                }
            };
            JclawRuntime.TurnResult result;
            try {
                result = runtime.submit(principal.tenant(), thread, parsed.inbound(),
                        new java.util.concurrent.atomic.AtomicBoolean(false), Optional.of(sink));
            } catch (java.io.UncheckedIOException clientGone) {
                return;
            }
            String finish = result.isSuccess() ? "stop" : "stop";
            if (!result.isSuccess()) {
                writeSse(out, mapper.writeValueAsString(OpenAiCompat.chunk(
                        id, created, requestedModel, describeOutcome(result), null)));
            }
            writeSse(out, mapper.writeValueAsString(OpenAiCompat.chunk(id, created, requestedModel, null, finish)));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void respondCompletion(
            HttpExchange exchange, JclawRuntime.TurnResult result, ThreadId thread,
            long created, String requestedModel) throws IOException {
        exchange.getResponseHeaders().add("X-Jclaw-Thread", thread.value());
        exchange.getResponseHeaders().add("X-Jclaw-Run", result.run().value());
        exchange.getResponseHeaders().add("X-Jclaw-Status", result.status().name());
        result.gatePrompt().ifPresent(gate -> exchange.getResponseHeaders().add("X-Jclaw-Gate", gate));
        if (result.status() == TurnStatus.FAILED || result.status() == TurnStatus.CANCELLED) {
            send(exchange, 502, OpenAiCompat.error(describeOutcome(result), "jclaw_run_failed"));
            return;
        }
        RunProjection.RunView view = projections.of(result.run(),
                () -> events.readRun(result.run()).stream().map(EventLog.Entry::event).toList());
        String content = result.reply().orElseGet(() -> describeOutcome(result));
        send(exchange, 200, OpenAiCompat.completion(
                "chatcmpl-" + result.run().value(), created, requestedModel, content, "stop",
                view.usage().inputTokens(), view.usage().outputTokens()));
    }

    /** What to tell an OpenAI client about a run that did not simply reply. */
    private static String describeOutcome(JclawRuntime.TurnResult result) {
        return switch (result.status()) {
            case BLOCKED_APPROVAL -> "[jclaw] The run is parked awaiting approval"
                    + result.gatePrompt().map(g -> " (gate " + g + ")").orElse("")
                    + ". Approve it and resume run " + result.run().value() + ".";
            case BLOCKED_AUTH -> "[jclaw] The run is parked awaiting provider credentials"
                    + result.gatePrompt().map(g -> " (gate " + g + ")").orElse("") + ".";
            case WAITING_PROCESS -> "[jclaw] The run is waiting on a child run; a worker resumes it.";
            default -> "[jclaw] The run " + result.status().name().toLowerCase(java.util.Locale.ROOT)
                    + result.failure().map(f -> " (" + f.category() + ")").orElse("")
                    + result.failureDetail().map(d -> ": " + d).orElse("") + ".";
        };
    }

    private static void writeSse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Fires a webhook routine: the routine named in the path, in the local project, whose trigger
     * is a webhook accepting the presented bearer secret. The body, bounded, joins the prompt.
     */
    private void webhook(HttpExchange exchange, String name) throws IOException {
        Optional<RoutineStore.Routine> routine = routines.flatMap(store ->
                store.list(runtime.scopeFor(new ThreadId("routines"))).stream()
                        .filter(r -> r.name().equals(name))
                        .filter(r -> Trigger.parse(r.trigger()).toOptional().orElse(null) instanceof Trigger.Webhook)
                        .findFirst());
        if (routine.isEmpty()) {
            send(exchange, 404, Map.of("error", "no such webhook"));
            return;
        }
        Trigger.Webhook trigger = (Trigger.Webhook) Trigger.parse(routine.get().trigger()).orElseThrow();
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String presented = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim() : null;
        if (!trigger.accepts(presented)) {
            send(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }
        if (!routine.get().enabled()) {
            send(exchange, 409, Map.of("error", "routine is paused"));
            return;
        }
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_WEBHOOK_BODY + 1);
        }
        boolean truncated = body.length > MAX_WEBHOOK_BODY;
        String payload = new String(body, 0, Math.min(body.length, MAX_WEBHOOK_BODY), StandardCharsets.UTF_8);
        String prompt = routine.get().prompt() + "\n\nWebhook payload"
                + (truncated ? " (truncated to " + MAX_WEBHOOK_BODY + " bytes)" : "") + ":\n```\n"
                + payload + "\n```";
        routines.get().recordFiring(routine.get().id(), clock.instant());
        TurnRunId run = runtime.enqueue(routine.get().thread(), ChatMessage.user(prompt));
        send(exchange, 202, Map.of("run", run.value(), "thread", routine.get().thread().value(),
                "status", "QUEUED"));
    }

    /**
     * Hands one webhook delivery to the channel service.
     *
     * <p>Answers immediately in every case: the platform is waiting, and the turn it may have
     * started will take as long as it takes. A refusal says nothing about why.
     */
    private void channel(HttpExchange exchange, String name) throws IOException {
        if (channels.isEmpty()) {
            send(exchange, 404, Map.of("error", "no such channel"));
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> {
            if (!values.isEmpty()) {
                headers.put(key.toLowerCase(java.util.Locale.ROOT), values.get(0));
            }
        });
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_CHANNEL_BODY);
        }
        var handled = channels.get().receive(name, headers, new String(body, StandardCharsets.UTF_8));
        switch (handled) {
            case io.jclaw.app.channel.ChannelService.Handled.Handshake handshake ->
                    sendText(exchange, "text/plain; charset=utf-8", handshake.body());
            case io.jclaw.app.channel.ChannelService.Handled.Accepted accepted ->
                    send(exchange, 202, Map.of("run", accepted.run(), "thread", accepted.thread()));
            case io.jclaw.app.channel.ChannelService.Handled.Ignored ignored ->
                    send(exchange, 200, Map.of("ignored", ignored.why()));
            case io.jclaw.app.channel.ChannelService.Handled.Refused refused ->
                    send(exchange, 401, Map.of("error", refused.reason()));
        }
    }

    private void sendText(HttpExchange exchange, String contentType, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // --- routes ---

    /** {@code POST /threads/{thread}/turns} with {@code {"text": …, "attachments": [{mediaType, data}]}}. */
    @SuppressWarnings("unchecked")
    private void submitTurn(HttpExchange exchange, Principal principal, ThreadId thread) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String text = body.get("text") instanceof String t ? t : "";
        List<ContentBlock> blocks = new ArrayList<>();
        if (!text.isBlank()) {
            blocks.add(new ContentBlock.Text(text));
        }
        if (body.get("attachments") instanceof List<?> attachments) {
            for (Object raw : attachments) {
                if (raw instanceof Map<?, ?> attachment) {
                    Map<String, Object> a = (Map<String, Object>) attachment;
                    blocks.add(new ContentBlock.Image(
                            String.valueOf(a.get("mediaType")), String.valueOf(a.get("data"))));
                }
            }
        }
        if (blocks.isEmpty()) {
            send(exchange, 400, Map.of("error", "text or attachments required"));
            return;
        }
        TurnRunId run;
        try {
            run = runtime.enqueue(principal.tenant(), thread, new ChatMessage(ChatMessage.Role.USER, blocks));
        } catch (JclawRuntime.TenantOverBudget e) {
            send(exchange, 429, Map.of("error", "token budget spent"));
            return;
        }
        send(exchange, 202, Map.of("run", run.value(), "thread", thread.value(), "status", "QUEUED"));
    }

    /** {@code GET /runs/{run}}: the projection, plus the reply when the run completed. */
    private void run(HttpExchange exchange, Principal principal, TurnRunId run) throws IOException {
        Optional<RunStore.RunRecord> record = runs.find(run).filter(r -> principal.mayRead(r.scope()));
        if (record.isEmpty()) {
            send(exchange, 404, Map.of("error", "no such run"));
            return;
        }
        send(exchange, 200, view(record.get()));
    }

    private Map<String, Object> view(RunStore.RunRecord record) {
        RunProjection.RunView view = projections.of(record.run(),
                () -> events.readRun(record.run()).stream().map(EventLog.Entry::event).toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", record.run().value());
        out.put("thread", record.scope().thread().value());
        out.put("status", record.status().name());
        out.put("projectedStatus", view.status().map(Enum::name).orElse(null));
        out.put("submittedAt", record.submittedAt().toString());
        record.finishedAt().ifPresent(at -> out.put("finishedAt", at.toString()));
        out.put("modelCalls", view.modelCalls());
        out.put("tokens", view.usage().total());
        out.put("iterations", view.iterations());
        out.put("capabilities", view.capabilities().stream().map(call -> Map.of(
                "capability", call.capability(), "effect", call.effect(),
                "outcome", call.outcome(), "latencyMillis", call.latencyMillis())).toList());
        out.put("injectionFindings", view.injectionFindings());
        view.openGate().ifPresent(gate -> out.put("gate", Map.of("kind", gate.kind().name(), "id", gate.id())));
        view.failure().ifPresent(failure -> out.put("failure", failure.category()));
        if (record.status() == TurnStatus.COMPLETED) {
            threads.history(record.scope().thread(), Integer.MAX_VALUE).stream()
                    .filter(m -> m.message().role() == ChatMessage.Role.ASSISTANT)
                    .filter(m -> !m.createdAt().isBefore(record.submittedAt()))
                    .reduce((first, second) -> second)
                    .ifPresent(m -> out.put("reply", m.message().displayText()));
        }
        return out;
    }

    /**
     * {@code GET /runs/{run}/events}: server-sent events following the log for one run.
     *
     * <p>Replays what is already there, then polls the log and pushes new entries until the run
     * reaches a terminal status or the client goes away. Each event is the same redacted record
     * the JSONL log holds, so a browser sees exactly what an operator would see in the file.
     */
    private void stream(HttpExchange exchange, TurnRunId run) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            int sent = 0;
            while (true) {
                List<EventLog.Entry> entries = events.readRun(run);
                for (int i = sent; i < entries.size(); i++) {
                    EventLog.Entry entry = entries.get(i);
                    String data = mapper.writeValueAsString(EventCodec.encode(entry.event()));
                    out.write(("id: " + entry.cursor().position() + "\nevent: " + entry.event().type()
                            + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                sent = entries.size();
                boolean terminal = runs.find(run).map(r -> r.status().isTerminal()).orElse(true);
                if (terminal && sent == events.readRun(run).size()) {
                    out.write("event: end\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return;
                }
                Thread.sleep(STREAM_POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGone) {
            // The subscriber disconnected; nothing to clean up, the log is the record.
        }
    }

    /** {@code POST /approvals/{gate}} with {@code {"approved": true|false}}: decide and requeue. */
    private void decide(HttpExchange exchange, Principal principal, GateId gate) throws IOException {
        Map<String, Object> body = readJson(exchange);
        boolean approved = body.get("approved") instanceof Boolean b && b;
        Optional<ApprovalStore.Gate> found = approvals.find(gate).filter(g -> principal.mayRead(g.scope()));
        if (found.isEmpty()) {
            send(exchange, 404, Map.of("error", "no such gate"));
            return;
        }
        ApprovalStore.Gate g = found.get();
        if (!g.isPending() || approvals.expired(g)) {
            send(exchange, 409, Map.of("error", g.isPending() ? "gate expired" : "gate already resolved"));
            return;
        }
        approvals.resolve(g.id(), approved);
        // Back to the queue: the scheduler resumes it, and the kernel re-authorizes against the
        // decision just recorded. Same path as the CLI, without executing in the request thread.
        boolean requeued = false;
        try {
            runs.updateStatus(g.run(), TurnStatus.QUEUED);
            requeued = true;
        } catch (IllegalStateException notParked) {
            // Already moved on; the decision still stands for the next dispatch.
        }
        send(exchange, 200, Map.of("gate", g.id().value(), "approved", approved,
                "run", g.run().value(), "requeued", requeued));
    }

    // --- rendering ---

    private Map<String, Object> gate(ApprovalStore.Gate gate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", gate.id().value());
        out.put("kind", gate.kind().name());
        out.put("run", gate.run().value());
        out.put("thread", gate.scope().thread().value());
        out.put("capability", gate.capability().value());
        out.put("prompt", gate.prompt());
        out.put("raisedAt", gate.raisedAt().toString());
        out.put("expiresAt", gate.expiresAt().toString());
        return out;
    }

    private Map<String, Object> message(ThreadService.ThreadMessage message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", message.id().value());
        out.put("role", message.message().role().name());
        out.put("text", message.message().displayText());
        out.put("attachments", message.message().content().stream()
                .filter(ContentBlock.Image.class::isInstance)
                .map(block -> ((ContentBlock.Image) block).mediaType()).toList());
        out.put("createdAt", message.createdAt().toString());
        return out;
    }

    // --- plumbing ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                return Map.of();
            }
            return mapper.readValue(bytes, Map.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed json", e);
        }
    }

    private void send(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Result helper for callers that want the HTTP shape of a runtime outcome. */
    static Map<String, Object> outcome(Result<?, String> result) {
        return result.fold(ok -> Map.of("ok", true), err -> Map.of("ok", false, "error", err));
    }
}
