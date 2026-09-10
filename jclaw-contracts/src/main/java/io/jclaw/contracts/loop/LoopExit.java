// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.loop;

import io.jclaw.contracts.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.contracts.turn.TurnRef.LoopGateRef;
import io.jclaw.contracts.turn.TurnRef.LoopMessageRef;
import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.contracts.turn.TurnStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A driver's <em>claim</em> about how a run ended. Not durable state, and not trusted.
 *
 * <p>A loop driver is userland code. It may be buggy, it may be a third-party family, and in the
 * limit it may be adversarial. So it cannot write run state directly; it returns one of these,
 * carrying nothing but host-minted refs, and the exit applier re-reads every ref before any
 * durable transition. A driver claiming {@code Completed} with a fabricated message ref does not
 * complete the run — it fails it with {@link FailureKind#DRIVER_PROTOCOL_VIOLATION}.
 *
 * <p>This is why no variant carries free text, prompts, tool output, or provider errors: there is
 * nothing here for a driver to smuggle unvalidated content through.
 */
public sealed interface LoopExit {

    /**
     * The loop finished and wrote a reply.
     *
     * @param replyRefs  assistant transcript messages, most recent last; must be non-empty and
     *                   every ref must resolve for the exit to validate
     * @param resultRefs durable capability results produced during the run
     */
    record Completed(List<LoopMessageRef> replyRefs, List<LoopResultRef> resultRefs) implements LoopExit {
        public Completed {
            replyRefs = List.copyOf(Objects.requireNonNull(replyRefs, "replyRefs"));
            resultRefs = List.copyOf(Objects.requireNonNull(resultRefs, "resultRefs"));
            if (replyRefs.isEmpty()) {
                throw new IllegalArgumentException("Completed requires at least one reply ref");
            }
        }

        public static Completed of(LoopMessageRef reply) {
            return new Completed(List.of(reply), List.of());
        }
    }

    /**
     * The loop parked on a gate and can resume from the checkpoint.
     *
     * <p>Both refs are mandatory: without the gate there is nothing for a human to resolve, and
     * without the checkpoint the run could not resume without redoing side effects.
     */
    record Blocked(GateKind gate, LoopGateRef gateRef, LoopCheckpointStateRef checkpointRef) implements LoopExit {
        public Blocked {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(gateRef, "gateRef");
            Objects.requireNonNull(checkpointRef, "checkpointRef");
        }
    }

    /**
     * The loop failed.
     *
     * @param kind  stable redacted category
     * @param cause short bounded hint, already sanitized. Never a stack trace or provider body.
     */
    record Failed(FailureKind kind, Optional<String> cause) implements LoopExit {
        private static final int MAX_CAUSE = 200;

        public Failed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(cause, "cause");
            cause = cause.map(c -> c.length() > MAX_CAUSE ? c.substring(0, MAX_CAUSE) : c);
        }

        public static Failed of(FailureKind kind) {
            return new Failed(kind, Optional.empty());
        }

        public static Failed of(FailureKind kind, String cause) {
            return new Failed(kind, Optional.ofNullable(cause));
        }
    }

    /** The loop observed cancellation at a safe point and stopped. */
    record Cancelled(Optional<LoopCheckpointStateRef> checkpointRef) implements LoopExit {
        public Cancelled {
            Objects.requireNonNull(checkpointRef, "checkpointRef");
        }

        public static Cancelled withoutCheckpoint() {
            return new Cancelled(Optional.empty());
        }
    }

    /**
     * The status this exit claims. The applier still validates evidence before writing it, so a
     * claim here is a request for a transition, not the transition itself.
     */
    default TurnStatus claimedStatus() {
        return switch (this) {
            case Completed ignored -> TurnStatus.COMPLETED;
            case Blocked blocked -> blocked.gate().blockedStatus();
            case Failed ignored -> TurnStatus.FAILED;
            case Cancelled ignored -> TurnStatus.CANCELLED;
        };
    }

    /** Whether this exit claims a terminal state. Blocked runs keep the active lock. */
    default boolean isTerminal() {
        return claimedStatus().isTerminal();
    }
}
