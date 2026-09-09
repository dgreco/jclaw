package io.jclaw.contracts.event;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A redacted lifecycle or progress event.
 *
 * <p>Every variant is constrained by construction to carry only ids, refs, enums, and counters.
 * There is deliberately nowhere to put a prompt, a tool argument, a file path, a URL, a provider
 * error body, or a secret — not because callers are trusted to omit them, but because the record
 * components do not exist. Redaction you have to remember to apply is redaction that eventually
 * gets forgotten.
 *
 * <p>The one concession is {@code detail} on failure variants, which is a bounded stable category
 * string, not free text from a backend.
 */
public sealed interface JclawEvent {

    /** When the event occurred. */
    Instant at();

    /** The run it belongs to. */
    TurnRunId run();

    /** A turn was admitted and queued. No side effect has occurred yet. */
    record TurnSubmitted(Instant at, TurnRunId run, TurnScope scope) implements JclawEvent {
        public TurnSubmitted {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /** A runner claimed the run and holds a lease. */
    record RunClaimed(Instant at, TurnRunId run, String workerId, Instant leaseExpiresAt) implements JclawEvent {
        public RunClaimed {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        }
    }

    /** A model exchange finished. Carries token accounting, never prompt or completion text. */
    record ModelCalled(
            Instant at,
            TurnRunId run,
            String providerId,
            String modelId,
            Usage usage,
            long latencyMillis) implements JclawEvent {

        public ModelCalled {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(usage, "usage");
        }
    }

    /**
     * A model exchange failed, after the provider sanitized the cause.
     *
     * @param detail bounded, already-sanitized hint from the provider boundary. Never a response
     *               body or stack trace — but enough to tell a reflection fault from an outage,
     *               which a bare category cannot.
     */
    record ModelFailed(Instant at, TurnRunId run, String providerId, String category,
                       Optional<String> detail) implements JclawEvent {
        public ModelFailed {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * A capability invocation completed.
     *
     * @param fingerprint identifies which exact invocation, without revealing its arguments
     * @param outcome     one of {@code ok}, {@code denied}, {@code failed}
     */
    record CapabilityInvoked(
            Instant at,
            TurnRunId run,
            CapabilityId capability,
            EffectClass effect,
            String fingerprint,
            String outcome,
            long latencyMillis) implements JclawEvent {

        public CapabilityInvoked {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * A vault secret was handed to a capability for one call.
     *
     * <p>The name is the audit trail: which credential went to which tool. The value, and the
     * arguments it was substituted into, are nowhere in the log.
     */
    record SecretInjected(Instant at, TurnRunId run, CapabilityId capability, String secret) implements JclawEvent {
        public SecretInjected {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(secret, "secret");
        }
    }

    /**
     * Tool output looked like a prompt injection.
     *
     * @param severity the worst finding, as a stable token ({@code LOW}, {@code MEDIUM}, {@code HIGH})
     * @param findings how many rules matched
     * @param action   what the kernel did: {@code warned}, {@code sanitized}, or {@code blocked}
     */
    record InjectionDetected(
            Instant at,
            TurnRunId run,
            CapabilityId capability,
            String severity,
            int findings,
            String action) implements JclawEvent {

        public InjectionDetected {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(action, "action");
        }
    }

    /** An approval or auth gate was raised; the run is parked. */
    record GateRaised(Instant at, TurnRunId run, GateKind gate, String gateId) implements JclawEvent {
        public GateRaised {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(gateId, "gateId");
        }
    }

    /** A gate was resolved by a human. */
    record GateResolved(Instant at, TurnRunId run, String gateId, boolean approved) implements JclawEvent {
        public GateResolved {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(gateId, "gateId");
        }
    }

    /** A checkpoint was persisted. The kind determines later recovery safety. */
    record CheckpointWritten(Instant at, TurnRunId run, CheckpointKind kind, int iteration) implements JclawEvent {
        public CheckpointWritten {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The run reached a terminal state. */
    record RunFinished(
            Instant at,
            TurnRunId run,
            TurnStatus status,
            Optional<FailureKind> failure,
            Usage totalUsage,
            int iterations) implements JclawEvent {

        public RunFinished {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(totalUsage, "totalUsage");
            if (!status.isTerminal()) {
                throw new IllegalArgumentException("RunFinished requires a terminal status, got " + status);
            }
        }
    }

    /** Stable event type token, used for filtering and projection routing. */
    default String type() {
        return switch (this) {
            case TurnSubmitted ignored -> "turn.submitted";
            case RunClaimed ignored -> "run.claimed";
            case ModelCalled ignored -> "model.called";
            case ModelFailed ignored -> "model.failed";
            case CapabilityInvoked ignored -> "capability.invoked";
            case InjectionDetected ignored -> "injection.detected";
            case SecretInjected ignored -> "secret.injected";
            case GateRaised ignored -> "gate.raised";
            case GateResolved ignored -> "gate.resolved";
            case CheckpointWritten ignored -> "checkpoint.written";
            case RunFinished ignored -> "run.finished";
        };
    }
}
