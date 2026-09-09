package io.jclaw.contracts.capability;

import io.jclaw.contracts.loop.GateKind;
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

    /**
     * A gate awaiting a human.
     *
     * <p>Two kinds share the record. An {@link GateKind#APPROVAL} gate names a capability
     * invocation by fingerprint and is resolved by a decision. An {@link GateKind#AUTH} gate names
     * the provider and the credential it lacks (as the {@code fingerprint}); it is "resolved" by
     * the credential appearing, so approving it merely records that the human says it has, and
     * resuming re-attempts the model call either way.
     */
    record Gate(
            GateId id,
            GateKind kind,
            TurnRunId run,
            TurnScope scope,
            CapabilityId capability,
            String fingerprint,
            String prompt,
            Instant raisedAt,
            Instant expiresAt,
            Optional<Boolean> approved) {

        public Gate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(raisedAt, "raisedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(approved, "approved");
        }

        public boolean isPending() {
            return approved.isEmpty();
        }

        /**
         * Whether an <em>undecided</em> gate has lapsed. A decision, once made, does not expire:
         * an exact-invocation grant stays a grant. What lapses is the open question, so a resume
         * after the TTL raises a fresh gate instead of answering a stale one.
         */
        public boolean isExpiredAt(Instant now) {
            return isPending() && !now.isBefore(expiresAt);
        }

        public boolean isApproved() {
            return approved.orElse(false);
        }

        public boolean isAuth() {
            return kind == GateKind.AUTH;
        }
    }

    /** Raises a new approval gate and returns it. */
    Gate raise(TurnRunId run, TurnScope scope, CapabilityInvocation invocation, String prompt);

    /**
     * Raises an authentication gate: the model provider refused for want of credentials, and the
     * run parks until a human supplies them and resumes it.
     *
     * @param providerId     which provider, recorded as the gate's capability {@code model.<id>}
     * @param credentialHint what is missing, e.g. the environment variable name; the fingerprint
     * @param prompt         what to show the human
     */
    Gate raiseAuth(TurnRunId run, TurnScope scope, String providerId, String credentialHint, String prompt);

    /**
     * Raises a process gate: the invocation started work that completes elsewhere, and the run
     * parks until it does. Keyed by the invocation fingerprint like an approval gate, so a resume
     * that re-dispatches the same call finds the same open gate.
     */
    Gate raiseProcess(TurnRunId run, TurnScope scope, CapabilityInvocation invocation, String prompt);

    /** Records a human decision. */
    void resolve(GateId gate, boolean approved);

    /** Looks up a gate by id. */
    Optional<Gate> find(GateId gate);

    /**
     * The most recent gate for this exact invocation in this scope, decided or not.
     *
     * <p>A decided gate is what lets a resumed run proceed without asking twice — and, because
     * the match is on fingerprint, what stops it from proceeding with anything else. An undecided
     * one lets the kernel park on the same open question rather than raise a duplicate, unless it
     * has expired.
     */
    Optional<Gate> findGrant(TurnScope scope, String fingerprint);

    /** Gates still awaiting a decision and not yet expired, newest first. */
    List<Gate> pending(TurnScope scope);
}
