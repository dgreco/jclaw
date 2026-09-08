package io.jclaw.contracts.capability;

import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.contracts.turn.TurnRunId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable home for full capability payloads, and the sole minter of {@link LoopResultRef}.
 *
 * <p>A capability may produce far more output than belongs in a model's context or an event
 * stream. The full payload is stored here once; everything upstream carries a ref plus a bounded
 * summary. That keeps large output out of the transcript without discarding it, and it is what
 * makes a {@code LoopExit} carrying result refs verifiable — the applier resolves them here.
 */
public interface CapabilityResultStore {

    /** A stored payload and its provenance. */
    record StoredResult(
            LoopResultRef ref,
            TurnRunId run,
            CapabilityId capability,
            String fingerprint,
            String payload,
            boolean truncated,
            Instant storedAt) {

        public StoredResult {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(storedAt, "storedAt");
        }
    }

    /**
     * Stores a payload and mints its ref.
     *
     * @param payload already redacted by the kernel; this store does not redact
     */
    LoopResultRef store(
            TurnRunId run, CapabilityInvocation invocation, String payload, boolean truncated);

    /** Resolves a ref. Empty means the ref is not evidence of anything. */
    Optional<StoredResult> resolve(LoopResultRef ref);
}
