package io.jclaw.app.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jclaw.app.runtime.JclawRuntime;
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
 * <p>Bound to loopback unless told otherwise, and when a token is configured every request must
 * carry it as a bearer. Built on the JDK's own HTTP server: one dependency fewer, and it works in
 * the native image.
 */
public final class JclawHttpServer {

    private static final Logger log = LoggerFactory.getLogger(JclawHttpServer.class);

    private static final Pattern THREAD_TURNS = Pattern.compile("^/threads/([^/]+)/turns$");
    private static final Pattern THREAD_MESSAGES = Pattern.compile("^/threads/([^/]+)/messages$");
    private static final Pattern RUN = Pattern.compile("^/runs/([^/]+)$");
    private static final Pattern RUN_EVENTS = Pattern.compile("^/runs/([^/]+)/events$");
    private static final Pattern APPROVAL = Pattern.compile("^/approvals/([^/]+)$");

    /** How often the event stream polls the log. */
    private static final long STREAM_POLL_MILLIS = 250;

    private final JclawRuntime runtime;
    private final RunStore runs;
    private final EventLog events;
    private final ThreadService threads;
    private final JsonlApprovalStore approvals;
    private final Clock clock;
    private final Optional<String> token;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;

    public JclawHttpServer(
            JclawRuntime runtime, RunStore runs, EventLog events, ThreadService threads,
            JsonlApprovalStore approvals, Clock clock, Optional<String> token) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.events = Objects.requireNonNull(events, "events");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.token = Objects.requireNonNull(token, "token").filter(t -> !t.isBlank());
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
            if (!authorized(exchange)) {
                send(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Matcher m;

            if (method.equals("GET") && path.equals("/health")) {
                send(exchange, 200, Map.of("status", "ok", "at", clock.instant().toString()));
            } else if (method.equals("POST") && (m = THREAD_TURNS.matcher(path)).matches()) {
                submitTurn(exchange, new ThreadId(m.group(1)));
            } else if (method.equals("GET") && (m = THREAD_MESSAGES.matcher(path)).matches()) {
                send(exchange, 200, Map.of("thread", m.group(1), "messages",
                        threads.history(new ThreadId(m.group(1)), Integer.MAX_VALUE).stream()
                                .map(this::message).toList()));
            } else if (method.equals("GET") && (m = RUN.matcher(path)).matches()) {
                run(exchange, new TurnRunId(m.group(1)));
            } else if (method.equals("GET") && (m = RUN_EVENTS.matcher(path)).matches()) {
                stream(exchange, new TurnRunId(m.group(1)));
            } else if (method.equals("GET") && path.equals("/approvals")) {
                send(exchange, 200, Map.of("gates", approvals.allPending().stream().map(this::gate).toList()));
            } else if (method.equals("POST") && (m = APPROVAL.matcher(path)).matches()) {
                decide(exchange, new GateId(m.group(1)));
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

    private boolean authorized(HttpExchange exchange) {
        if (token.isEmpty()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.equals("Bearer " + token.get());
    }

    // --- routes ---

    /** {@code POST /threads/{thread}/turns} with {@code {"text": …, "attachments": [{mediaType, data}]}}. */
    @SuppressWarnings("unchecked")
    private void submitTurn(HttpExchange exchange, ThreadId thread) throws IOException {
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
        TurnRunId run = runtime.enqueue(thread, new ChatMessage(ChatMessage.Role.USER, blocks));
        send(exchange, 202, Map.of("run", run.value(), "thread", thread.value(), "status", "QUEUED"));
    }

    /** {@code GET /runs/{run}}: the projection, plus the reply when the run completed. */
    private void run(HttpExchange exchange, TurnRunId run) throws IOException {
        Optional<RunStore.RunRecord> record = runs.find(run);
        if (record.isEmpty()) {
            send(exchange, 404, Map.of("error", "no such run"));
            return;
        }
        send(exchange, 200, view(record.get()));
    }

    private Map<String, Object> view(RunStore.RunRecord record) {
        RunProjection.RunView view = RunProjection.fold(record.run(),
                events.readRun(record.run()).stream().map(EventLog.Entry::event).toList());
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
    private void decide(HttpExchange exchange, GateId gate) throws IOException {
        Map<String, Object> body = readJson(exchange);
        boolean approved = body.get("approved") instanceof Boolean b && b;
        Optional<ApprovalStore.Gate> found = approvals.find(gate);
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
