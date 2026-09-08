package io.jclaw.contracts.capability;

import io.jclaw.contracts.turn.GateId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable record of approval gates and the decisions humans make on them.
 *
 * <p>Approvals are keyed by <em>invocation fingerprint</em>, not by capability. Approving one
 * {@code shell} command must not approve the next one, and this store is where that discipline is
 * enforced: {@link #findGrant} matches on the exact fingerprint the human was shown.
 */
public interface ApprovalStore {

    /** A gate awaiting a human decision. */
    record Gate(
            GateId id,
            TurnRunId run,
            TurnScope scope,
            CapabilityId capability,
            String fingerprint,
            String prompt,
            Instant raisedAt,
            Optional<Boolean> approved) {

        public Gate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(raisedAt, "raisedAt");
            Objects.requireNonNull(approved, "approved");
        }

        public boolean isPending() {
            return approved.isEmpty();
        }

        public boolean isApproved() {
            return approved.orElse(false);
        }
    }

    /** Raises a new gate and returns it. */
    Gate raise(TurnRunId run, TurnScope scope, CapabilityInvocation invocation, String prompt);

    /** Records a human decision. */
    void resolve(GateId gate, boolean approved);

    /** Looks up a gate by id. */
    Optional<Gate> find(GateId gate);

    /**
     * An approval already granted for this exact invocation in this scope, if any.
     *
     * <p>This is what lets a resumed run proceed without asking twice — and, because the match is
     * on fingerprint, what stops it from proceeding with anything else.
     */
    Optional<Gate> findGrant(TurnScope scope, String fingerprint);

    /** Gates still awaiting a decision, newest first. */
    List<Gate> pending(TurnScope scope);
}
