package io.jclaw.storage.approval;

import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.turn.GateId;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.storage.jsonl.JsonlFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable {@link ApprovalStore} over an append-only JSONL file.
 *
 * <p>Durability is not a nicety here, it is what makes the approval flow work at all. {@code jclaw
 * run} parks on a gate and <em>exits</em>; the human then runs {@code jclaw approvals approve} in a
 * different process, and a third process resumes the run. An in-memory store loses the gate at the
 * first process boundary, which makes interactive mode unusable rather than merely forgetful.
 *
 * <p>The log is append-only, so a decision is written as a second {@code resolved} record rather
 * than by rewriting the gate. The current state of a gate is the fold of its records — which keeps
 * the full history of who decided what, and when, instead of silently overwriting it.
 */
public final class JsonlApprovalStore implements ApprovalStore {

    private static final String KIND_RAISED = "raised";
    private static final String KIND_RESOLVED = "resolved";

    /** How long an unanswered gate stays answerable when no TTL is configured. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final JsonlFile file;
    private final Clock clock;
    private final Duration ttl;

    public JsonlApprovalStore(JsonlFile file, Clock clock) {
        this(file, clock, DEFAULT_TTL);
    }

    /** @param ttl how long a raised gate may go unanswered before a resume asks afresh */
    public JsonlApprovalStore(JsonlFile file, Clock clock, Duration ttl) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("approval ttl must be positive, got " + ttl);
        }
    }

    /** Whether a gate has lapsed by this store's clock. */
    public boolean expired(Gate gate) {
        return gate.isExpiredAt(clock.instant());
    }

    @Override
    public Gate raise(TurnRunId run, TurnScope scope, CapabilityInvocation invocation, String prompt) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(prompt, "prompt");

        Gate gate = new Gate(
                GateId.fresh(),
                GateKind.APPROVAL,
                run,
                scope,
                invocation.capability(),
                invocation.fingerprint(),
                prompt,
                clock.instant(),
                clock.instant().plus(ttl),
                Optional.empty());
        append(gate);
        return gate;
    }

    @Override
    public Gate raiseAuth(TurnRunId run, TurnScope scope, String providerId, String credentialHint, String prompt) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(credentialHint, "credentialHint");
        Objects.requireNonNull(prompt, "prompt");

        Gate gate = new Gate(
                GateId.fresh(),
                GateKind.AUTH,
                run,
                scope,
                CapabilityId.of("model." + providerId.toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9_]", "_")),
                credentialHint,
                prompt,
                clock.instant(),
                clock.instant().plus(ttl),
                Optional.empty());
        append(gate);
        return gate;
    }

    @Override
    public Gate raiseProcess(TurnRunId run, TurnScope scope, CapabilityInvocation invocation, String prompt) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(prompt, "prompt");

        Gate gate = new Gate(
                GateId.fresh(),
                GateKind.PROCESS,
                run,
                scope,
                invocation.capability(),
                invocation.fingerprint(),
                prompt,
                clock.instant(),
                clock.instant().plus(ttl),
                Optional.empty());
        append(gate);
        return gate;
    }

    private void append(Gate gate) {
        TurnScope scope = gate.scope();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("kind", KIND_RAISED);
        record.put("gateKind", gate.kind().name());
        record.put("id", gate.id().value());
        record.put("run", gate.run().value());
        record.put("tenant", scope.tenant());
        record.put("agent", scope.agent());
        record.put("project", scope.project());
        record.put("thread", scope.thread().value());
        record.put("capability", gate.capability().value());
        record.put("fingerprint", gate.fingerprint());
        record.put("prompt", gate.prompt());
        record.put("raisedAt", gate.raisedAt().toString());
        record.put("expiresAt", gate.expiresAt().toString());
        file.append(record);
    }

    @Override
    public void resolve(GateId gate, boolean approved) {
        Objects.requireNonNull(gate, "gate");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("kind", KIND_RESOLVED);
        record.put("id", gate.value());
        record.put("approved", approved);
        record.put("resolvedAt", clock.instant().toString());
        file.append(record);
    }

    @Override
    public Optional<Gate> find(GateId gate) {
        Objects.requireNonNull(gate, "gate");
        return Optional.ofNullable(replay().get(gate.value()));
    }

    @Override
    public Optional<Gate> findGrant(TurnScope scope, String fingerprint) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fingerprint, "fingerprint");
        // Most recent decision for this exact invocation in this scope wins.
        return replay().values().stream()
                .filter(gate -> gate.scope().equals(scope))
                .filter(gate -> gate.fingerprint().equals(fingerprint))
                .max(Comparator.comparing(Gate::raisedAt));
    }

    @Override
    public List<Gate> pending(TurnScope scope) {
        Objects.requireNonNull(scope, "scope");
        Instant now = clock.instant();
        return replay().values().stream()
                .filter(gate -> gate.scope().equals(scope))
                .filter(Gate::isPending)
                .filter(gate -> !gate.isExpiredAt(now))
                .sorted(Comparator.comparing(Gate::raisedAt).reversed())
                .toList();
    }

    /** Every answerable pending gate across all scopes, newest first. Backs {@code approvals list}. */
    public List<Gate> allPending() {
        return allPending(false);
    }

    /** As {@link #allPending()}, optionally including gates that have lapsed. */
    public List<Gate> allPending(boolean includeExpired) {
        Instant now = clock.instant();
        return replay().values().stream()
                .filter(Gate::isPending)
                .filter(gate -> includeExpired || !gate.isExpiredAt(now))
                .sorted(Comparator.comparing(Gate::raisedAt).reversed())
                .toList();
    }

    /**
     * Folds the log into current gate state.
     *
     * <p>Re-read per call rather than cached: another process may have written a decision since,
     * and a stale cache here would let an already-denied invocation proceed.
     */
    private Map<String, Gate> replay() {
        Map<String, Gate> gates = new LinkedHashMap<>();
        for (Map<String, Object> record : file.readAll()) {
            try {
                String kind = String.valueOf(record.get("kind"));
                String id = String.valueOf(record.get("id"));
                if (KIND_RAISED.equals(kind)) {
                    gates.put(id, toGate(record, id));
                } else if (KIND_RESOLVED.equals(kind)) {
                    Gate existing = gates.get(id);
                    if (existing != null) {
                        boolean approved = record.get("approved") instanceof Boolean flag && flag;
                        gates.put(id, withDecision(existing, approved));
                    }
                }
            } catch (RuntimeException e) {
                // A damaged line loses one gate, not the whole approval history.
            }
        }
        return gates;
    }

    private Gate toGate(Map<String, Object> record, String id) {
        Instant raisedAt = Instant.parse(String.valueOf(record.get("raisedAt")));
        return new Gate(
                new GateId(id),
                // Rows written before auth gates existed carry no kind and are approvals.
                record.get("gateKind") instanceof String kind ? GateKind.valueOf(kind) : GateKind.APPROVAL,
                new TurnRunId(String.valueOf(record.get("run"))),
                new TurnScope(
                        String.valueOf(record.get("tenant")),
                        String.valueOf(record.get("agent")),
                        String.valueOf(record.get("project")),
                        new ThreadId(String.valueOf(record.get("thread")))),
                CapabilityId.of(String.valueOf(record.get("capability"))),
                String.valueOf(record.get("fingerprint")),
                String.valueOf(record.get("prompt")),
                raisedAt,
                // Rows written before expiry existed get the current TTL from when they were raised.
                record.get("expiresAt") instanceof String at ? Instant.parse(at) : raisedAt.plus(ttl),
                Optional.empty());
    }

    private static Gate withDecision(Gate gate, boolean approved) {
        return new Gate(
                gate.id(), gate.kind(), gate.run(), gate.scope(), gate.capability(), gate.fingerprint(),
                gate.prompt(), gate.raisedAt(), gate.expiresAt(), Optional.of(approved));
    }

    /** Gates for one run, oldest first. */
    public List<Gate> forRun(TurnRunId run) {
        Objects.requireNonNull(run, "run");
        List<Gate> gates = new ArrayList<>(replay().values());
        gates.removeIf(gate -> !gate.run().equals(run));
        gates.sort(Comparator.comparing(Gate::raisedAt));
        return List.copyOf(gates);
    }
}
