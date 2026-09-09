package io.jclaw.domain.projection;

import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure read model of one run, folded from its events.
 *
 * <p>The event log is the record of what happened; this is the shape a product surface wants to
 * show for it. Folding is a total function over the event list, so the same events always yield
 * the same view, and a view can be rebuilt from the log at any time: there is no second store to
 * keep in sync, which is what makes projections cheap to add and impossible to corrupt.
 *
 * <p>Status is derived from lifecycle events alone. It may lag the run store by one transition
 * when a process died between writing the store and appending the event, which is exactly the
 * discrepancy an operator would want to see rather than have papered over.
 */
public final class RunProjection {

    private RunProjection() {
    }

    /** One capability invocation as the audit log recorded it. */
    public record CapabilityCall(String capability, String effect, String outcome, long latencyMillis) {
        public CapabilityCall {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** A gate the run parked on and whether it has been resolved. */
    public record Gate(GateKind kind, String id, Optional<Boolean> approved) {
        public Gate {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(approved, "approved");
        }
    }

    /** The view. */
    public record RunView(
            TurnRunId run,
            Optional<TurnScope> scope,
            Optional<TurnStatus> status,
            Optional<Instant> submittedAt,
            Optional<Instant> finishedAt,
            Optional<Instant> lastEventAt,
            int modelCalls,
            int modelFailures,
            Usage usage,
            List<CapabilityCall> capabilities,
            List<Gate> gates,
            int injectionFindings,
            int checkpoints,
            int iterations,
            Optional<FailureKind> failure) {

        public RunView {
            Objects.requireNonNull(run, "run");
            capabilities = List.copyOf(capabilities);
            gates = List.copyOf(gates);
        }

        /** The gate the run is currently parked on, if the last lifecycle event was a park. */
        public Optional<Gate> openGate() {
            return status.filter(TurnStatus::isBlocked)
                    .flatMap(ignored -> gates.stream().filter(gate -> gate.approved().isEmpty())
                            .reduce((first, second) -> second));
        }
    }

    /** Folds a run's events, in log order, into a view. */
    public static RunView fold(TurnRunId run, List<JclawEvent> events) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(events, "events");

        Optional<TurnScope> scope = Optional.empty();
        Optional<TurnStatus> status = Optional.empty();
        Optional<Instant> submittedAt = Optional.empty();
        Optional<Instant> finishedAt = Optional.empty();
        Optional<Instant> lastEventAt = Optional.empty();
        int modelCalls = 0;
        int modelFailures = 0;
        Usage usage = Usage.ZERO;
        List<CapabilityCall> capabilities = new ArrayList<>();
        List<Gate> gates = new ArrayList<>();
        int injections = 0;
        int checkpoints = 0;
        int iterations = 0;
        Optional<FailureKind> failure = Optional.empty();

        for (JclawEvent event : events) {
            if (!event.run().equals(run)) {
                continue;
            }
            lastEventAt = Optional.of(event.at());
            switch (event) {
                case JclawEvent.TurnSubmitted e -> {
                    scope = Optional.of(e.scope());
                    submittedAt = Optional.of(e.at());
                    status = Optional.of(TurnStatus.QUEUED);
                }
                case JclawEvent.RunClaimed ignored -> status = Optional.of(TurnStatus.RUNNING);
                case JclawEvent.ModelCalled e -> {
                    modelCalls++;
                    usage = usage.plus(e.usage());
                }
                case JclawEvent.ModelFailed ignored -> modelFailures++;
                case JclawEvent.CapabilityInvoked e -> capabilities.add(new CapabilityCall(
                        e.capability().value(), e.effect().name(), e.outcome(), e.latencyMillis()));
                case JclawEvent.InjectionDetected e -> injections += e.findings();
                case JclawEvent.SecretInjected ignored -> { }
                case JclawEvent.GateRaised e -> {
                    gates.add(new Gate(e.gate(), e.gateId(), Optional.empty()));
                    status = Optional.of(e.gate().blockedStatus());
                }
                case JclawEvent.GateResolved e -> {
                    for (int i = 0; i < gates.size(); i++) {
                        if (gates.get(i).id().equals(e.gateId())) {
                            gates.set(i, new Gate(gates.get(i).kind(), e.gateId(), Optional.of(e.approved())));
                        }
                    }
                }
                case JclawEvent.CheckpointWritten e -> {
                    checkpoints++;
                    iterations = Math.max(iterations, e.iteration());
                }
                case JclawEvent.RunFinished e -> {
                    status = Optional.of(e.status());
                    finishedAt = Optional.of(e.at());
                    failure = e.failure();
                    iterations = Math.max(iterations, e.iterations());
                }
            }
        }
        return new RunView(run, scope, status, submittedAt, finishedAt, lastEventAt, modelCalls,
                modelFailures, usage, capabilities, gates, injections, checkpoints, iterations, failure);
    }
}
