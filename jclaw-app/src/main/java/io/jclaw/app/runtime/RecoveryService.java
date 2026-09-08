package io.jclaw.app.runtime;

import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.CheckpointStore;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.recovery.LeaseRecovery;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies {@link LeaseRecovery} decisions to runs abandoned by a dead worker.
 *
 * <p>The judgement is entirely in the pure decision function; this class only carries it out. That
 * split is the point — the rules about replay safety and grace periods are the part worth testing
 * exhaustively, and they are testable here without killing a process or waiting two minutes.
 *
 * <p>A requeued run moves to {@code QUEUED} and has its lease released, making it claimable again.
 * It is <em>not</em> executed here: recovery decides what is safe, and something else — a worker,
 * or an operator running {@code jclaw resume} — decides when to act on it.
 */
@Service
public class RecoveryService {

    private final RunStore runs;
    private final CheckpointStore checkpoints;
    private final Clock clock;

    public RecoveryService(RunStore runs, CheckpointStore checkpoints, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What the sweep decided and did. */
    public record Outcome(LeaseRecovery.Decision decision, boolean applied) {
    }

    /**
     * Examines every expired lease and applies the resulting decision.
     *
     * @param dryRun when true, decide and report without changing anything
     */
    public List<Outcome> sweep(boolean dryRun) {
        List<RunStore.RunRecord> expired = runs.expiredLeases(clock.instant());
        if (expired.isEmpty()) {
            return List.of();
        }

        List<LeaseRecovery.Decision> decisions = LeaseRecovery.decideAll(
                expired, this::latestCheckpointKind, JclawRuntime.LEASE_TTL, clock.instant());

        return decisions.stream()
                .map(decision -> new Outcome(decision, !dryRun && apply(decision)))
                .toList();
    }

    /** Applies one decision. Returns whether durable state changed. */
    private boolean apply(LeaseRecovery.Decision decision) {
        try {
            return switch (decision) {
                case LeaseRecovery.Decision.Requeue requeue -> {
                    // Back to QUEUED and unclaimed, so a worker can pick it up and resume from the
                    // checkpoint that was proven replay-safe.
                    runs.updateStatus(requeue.run(), TurnStatus.QUEUED);
                    runs.releaseLease(requeue.run());
                    yield true;
                }
                case LeaseRecovery.Decision.FailTerminal failed -> {
                    runs.updateStatus(failed.run(), TurnStatus.FAILED);
                    runs.releaseLease(failed.run());
                    yield true;
                }
                case LeaseRecovery.Decision.LeaveAlone ignored -> false;
            };
        } catch (IllegalStateException e) {
            // The run transitioned underneath us — another process got there first. That is the
            // lifecycle guard doing its job, not an error worth propagating.
            return false;
        }
    }

    private Optional<CheckpointKind> latestCheckpointKind(TurnRunId run) {
        return checkpoints.latestFor(run).map(CheckpointStore.Checkpoint::kind);
    }
}
