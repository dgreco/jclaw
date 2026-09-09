package io.jclaw.domain.loop;

import io.jclaw.contracts.capability.CapabilityOutcome;
import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.contracts.turn.TurnRef.LoopGateRef;
import io.jclaw.contracts.turn.TurnRef.LoopMessageRef;

import java.util.List;
import java.util.Objects;

/**
 * What the interpreter reports back after carrying out a {@link LoopDecision}.
 *
 * <p>Observations are the only way the outside world enters the machine. Everything
 * non-deterministic — model output, capability results, minted refs, cancellation, the clock —
 * arrives here as a value. Feed the same observation sequence twice and the machine produces the
 * same decisions, which is the reproducibility guarantee the domain package promises.
 */
public sealed interface Observation {

    /** Begin execution. The only valid observation in {@code START}. */
    record Start() implements Observation {
    }

    /**
     * Continue a run rehydrated from a checkpoint. The only valid observation in
     * {@code RESUMING}.
     */
    record Resumed() implements Observation {
    }

    /** The model replied successfully. */
    record ModelReplied(ModelResponse response) implements Observation {
        public ModelReplied {
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * The model call failed, already sanitized by the provider.
     *
     * @param retryable whether the machine may try again within its failure budget
     * @param detail    bounded, already-sanitized explanation. Carried so the reason reaches the
     *                  user with the failure itself, rather than only in the event log where they
     *                  have to know to go looking for it.
     */
    record ModelFailed(FailureKind kind, boolean retryable, java.util.Optional<String> detail)
            implements Observation {

        public ModelFailed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }

        public ModelFailed(FailureKind kind, boolean retryable) {
            this(kind, retryable, java.util.Optional.empty());
        }
    }

    /**
     * The model provider refused for want of credentials, and the interpreter raised an auth gate.
     *
     * <p>Distinct from {@link ModelFailed} because the right response is different: not a retry
     * and not a failure, but parking the run on the gate so it can continue once a human has
     * supplied what was missing. Resuming re-attempts the model call.
     */
    record AuthRequired(LoopGateRef gateRef) implements Observation {
        public AuthRequired {
            Objects.requireNonNull(gateRef, "gateRef");
        }
    }

    /** One capability finished. */
    record CallOutcome(String callId, CapabilityOutcome outcome) {
        public CallOutcome {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** Every dispatched capability finished, in request order. */
    record CapabilitiesCompleted(List<CallOutcome> outcomes) implements Observation {
        public CapabilitiesCompleted {
            outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
        }
    }

    /** The transcript write completed and the host minted a ref. */
    record ReplyPersisted(LoopMessageRef ref) implements Observation {
        public ReplyPersisted {
            Objects.requireNonNull(ref, "ref");
        }
    }

    /** The checkpoint write completed. */
    record Checkpointed(LoopCheckpointStateRef ref, CheckpointKind kind) implements Observation {
        public Checkpointed {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * Cancellation was requested and observed at a safe point.
     *
     * <p>Delivered by the interpreter between effects, never mid-effect, so the machine can stop
     * without leaving a dispatched capability unaccounted for.
     */
    record CancelRequested() implements Observation {
    }

    /** Short stable token for tracing. */
    default String type() {
        return switch (this) {
            case Start ignored -> "start";
            case Resumed ignored -> "resumed";
            case ModelReplied ignored -> "model.replied";
            case ModelFailed ignored -> "model.failed";
            case AuthRequired ignored -> "auth.required";
            case CapabilitiesCompleted ignored -> "capabilities.completed";
            case ReplyPersisted ignored -> "reply.persisted";
            case Checkpointed ignored -> "checkpointed";
            case CancelRequested ignored -> "cancel.requested";
        };
    }
}
