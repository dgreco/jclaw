package io.jclaw.domain.recovery;

import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.turn.RunStore.RunRecord;
import io.jclaw.contracts.turn.TurnRunId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides what to do with a run whose worker stopped heartbeating. Pure.
 *
 * <p>This is the most consequential judgement in the harness, and it is deliberately a function of
 * values so it can be tested exhaustively without killing real processes. The question it answers:
 * <em>a worker died holding this run — is it safe to run it again?</em>
 *
 * <p>The rules follow IronClaw's, and each exists because the alternative is worse:
 *
 * <ul>
 *   <li><b>Only a replay-safe checkpoint may be requeued.</b> If the last checkpoint was taken
 *       before a model call or before a gate, nothing escaped the process, so re-running repeats
 *       nothing. Any other checkpoint — or none — means an effect may already have happened, and
 *       the run becomes a terminal sanitized failure the user resubmits explicitly. Automatic
 *       retry of side-effecting work is never correct: a duplicated file write or a duplicated
 *       outbound request cannot be un-sent.</li>
 *   <li><b>A full lease TTL of grace before requeueing.</b> An expired lease does not prove the
 *       worker is dead — it may be paused, swapping, or on a stalled disk. Waiting one further TTL
 *       past expiry means any worker still alive would have renewed. Without this, recovery
 *       becomes the very duplication it exists to prevent.</li>
 *   <li><b>An unknown checkpoint kind fails closed.</b> Recovery must never guess in the
 *       permissive direction.</li>
 * </ul>
 */
public final class LeaseRecovery {

    private LeaseRecovery() {
    }

    /** What the reconciler should do with one run. */
    public sealed interface Decision {

        /** Return to the queue for resume. Only ever chosen for provably replay-safe state. */
        record Requeue(TurnRunId run, CheckpointKind from) implements Decision {
            public Requeue {
                Objects.requireNonNull(run, "run");
                Objects.requireNonNull(from, "from");
            }
        }

        /** Move to a terminal failure with a sanitized reason. */
        record FailTerminal(TurnRunId run, FailureKind kind, String reason) implements Decision {
            public FailTerminal {
                Objects.requireNonNull(run, "run");
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** Nothing to do — and why, so the reconciler can report rather than silently skip. */
        record LeaveAlone(TurnRunId run, String reason) implements Decision {
            public LeaveAlone {
                Objects.requireNonNull(run, "run");
                Objects.requireNonNull(reason, "reason");
            }
        }

        default TurnRunId run() {
            return switch (this) {
                case Requeue requeue -> requeue.run;
                case FailTerminal failed -> failed.run;
                case LeaveAlone left -> left.run;
            };
        }

        /** Whether this decision changes durable state. */
        default boolean isActionable() {
            return !(this instanceof LeaveAlone);
        }
    }

    /**
     * Decides for one run.
     *
     * @param latestCheckpoint kind of the most recent checkpoint, empty when the run never wrote one
     * @param leaseTtl         the lease duration, used to compute the grace period
     * @param now              current time, supplied so the decision is reproducible
     */
    public static Decision decide(
            RunRecord record,
            Optional<CheckpointKind> latestCheckpoint,
            Duration leaseTtl,
            Instant now) {

        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(latestCheckpoint, "latestCheckpoint");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        Objects.requireNonNull(now, "now");

        TurnRunId run = record.run();

        if (record.status().isTerminal()) {
            return new Decision.LeaveAlone(run, "already terminal");
        }
        if (record.lease().isEmpty()) {
            // Queued or parked work is not held by anyone; there is nothing to recover.
            return new Decision.LeaveAlone(run, "no lease held");
        }
        if (!record.hasExpiredLease(now)) {
            return new Decision.LeaveAlone(run, "lease still valid");
        }
        if (record.status().isBlocked()) {
            // A parked run is waiting on a human, not on a worker. Its lease lapsing is expected
            // and means nothing; recovering it would restart work nobody has approved.
            return new Decision.LeaveAlone(run, "parked on a gate, not executing");
        }

        Instant expiredAt = record.lease().orElseThrow().expiresAt();
        boolean pastGrace = !now.isBefore(expiredAt.plus(leaseTtl));
        if (!pastGrace) {
            return new Decision.LeaveAlone(run, "within grace period; worker may still be alive");
        }

        return latestCheckpoint
                .filter(CheckpointKind::replaysNoSideEffect)
                .<Decision>map(kind -> new Decision.Requeue(run, kind))
                .orElseGet(() -> new Decision.FailTerminal(
                        run,
                        FailureKind.LEASE_EXPIRED,
                        latestCheckpoint
                                .map(kind -> "checkpoint " + kind + " may have side effects")
                                .orElse("no checkpoint to resume from")));
    }

    /** Convenience over a batch. Order is preserved so reporting is stable. */
    public static List<Decision> decideAll(
            List<RunRecord> records,
            java.util.function.Function<TurnRunId, Optional<CheckpointKind>> checkpointLookup,
            Duration leaseTtl,
            Instant now) {

        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(checkpointLookup, "checkpointLookup");
        return records.stream()
                .map(record -> decide(record, checkpointLookup.apply(record.run()), leaseTtl, now))
                .toList();
    }
}
