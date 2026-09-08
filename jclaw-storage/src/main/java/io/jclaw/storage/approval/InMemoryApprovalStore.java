package io.jclaw.storage.approval;

import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.turn.GateId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ApprovalStore}.
 *
 * <p>Approvals are scoped to a process lifetime here. For the interactive CLI that is the correct
 * behaviour rather than a shortcut: an approval granted in one session should not silently persist
 * into the next one, where the user has forgotten what they authorized. A hosted deployment that
 * needs durable, auditable approvals should swap in a database-backed implementation — which is
 * exactly why this is behind a port.
 *
 * <p>Grants are keyed by {@code scope + fingerprint}, so approving one invocation authorizes that
 * invocation and nothing else.
 */
public final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, Gate> byId = new ConcurrentHashMap<>();
    private final Map<String, GateId> byGrantKey = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryApprovalStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Gate raise(TurnRunId run, TurnScope scope, CapabilityInvocation invocation, String prompt) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(prompt, "prompt");

        String fingerprint = invocation.fingerprint();
        Gate gate = new Gate(
                GateId.fresh(),
                run,
                scope,
                invocation.capability(),
                fingerprint,
                prompt,
                clock.instant(),
                Optional.empty());

        byId.put(gate.id().value(), gate);
        byGrantKey.put(grantKey(scope, fingerprint), gate.id());
        return gate;
    }

    @Override
    public void resolve(GateId gate, boolean approved) {
        Objects.requireNonNull(gate, "gate");
        byId.computeIfPresent(gate.value(), (ignored, existing) -> new Gate(
                existing.id(),
                existing.run(),
                existing.scope(),
                existing.capability(),
                existing.fingerprint(),
                existing.prompt(),
                existing.raisedAt(),
                Optional.of(approved)));
    }

    @Override
    public Optional<Gate> find(GateId gate) {
        Objects.requireNonNull(gate, "gate");
        return Optional.ofNullable(byId.get(gate.value()));
    }

    @Override
    public Optional<Gate> findGrant(TurnScope scope, String fingerprint) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fingerprint, "fingerprint");
        return Optional.ofNullable(byGrantKey.get(grantKey(scope, fingerprint)))
                .map(GateId::value)
                .map(byId::get);
    }

    @Override
    public List<Gate> pending(TurnScope scope) {
        Objects.requireNonNull(scope, "scope");
        return byId.values().stream()
                .filter(gate -> gate.scope().equals(scope))
                .filter(Gate::isPending)
                .sorted(Comparator.comparing(Gate::raisedAt).reversed())
                .toList();
    }

    private static String grantKey(TurnScope scope, String fingerprint) {
        return scope.lockKey() + '#' + fingerprint;
    }
}
