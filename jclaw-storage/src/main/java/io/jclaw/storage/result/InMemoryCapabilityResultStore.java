package io.jclaw.storage.result;

import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.CapabilityResultStore;
import io.jclaw.contracts.turn.Ident;
import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.contracts.turn.TurnRunId;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link CapabilityResultStore}.
 *
 * <p>Result payloads are per-run working data: the loop reads them back within the same run, and
 * the exit applier resolves the refs at the end of it. Keeping them in memory keeps large tool
 * output out of the durable log, which is where it would otherwise accumulate without ever being
 * read again.
 *
 * <p>This is the sole minter of {@link LoopResultRef}. Refs are unguessable so a driver cannot
 * fabricate one that resolves — the security property holds because resolution is a lookup here,
 * not a format check.
 */
public final class InMemoryCapabilityResultStore implements CapabilityResultStore {

    private final Map<String, StoredResult> byRef = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryCapabilityResultStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public LoopResultRef store(
            TurnRunId run, CapabilityInvocation invocation, String payload, boolean truncated) {

        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(payload, "payload");

        LoopResultRef ref = new LoopResultRef(Ident.fresh("res"));
        byRef.put(ref.value(), new StoredResult(
                ref,
                run,
                invocation.capability(),
                invocation.fingerprint(),
                payload,
                truncated,
                clock.instant()));
        return ref;
    }

    @Override
    public Optional<StoredResult> resolve(LoopResultRef ref) {
        Objects.requireNonNull(ref, "ref");
        return Optional.ofNullable(byRef.get(ref.value()));
    }

    /** Number of stored results. Exposed for diagnostics and tests. */
    public int size() {
        return byRef.size();
    }
}
