package io.jclaw.domain.recovery;

import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.turn.RunStore.Lease;
import io.jclaw.contracts.turn.RunStore.RunRecord;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery rules, tested by passing instants rather than by killing processes.
 *
 * <p>This is the payoff of keeping the decision pure: "what happens when a worker dies two seconds
 * after writing an AFTER_CAPABILITY checkpoint" is an assertion here, and would otherwise be a
 * race nobody can reproduce on demand.
 */
class LeaseRecoveryTest {

    private static final Instant T0 = Instant.parse("2026-05-01T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final TurnScope SCOPE = TurnScope.local("proj", new ThreadId("t"));
    private static final TurnRunId RUN = new TurnRunId("run_abc");

    private static RunRecord record(TurnStatus status, Optional<Lease> lease) {
        return new RunRecord(RUN, SCOPE, status, "m", "s", T0, Optional.empty(), lease);
    }

    /** A lease that expired {@code agoSeconds} before T0. */
    private static Optional<Lease> expired(long agoSeconds) {
        return Optional.of(new Lease("dead-worker", T0.minusSeconds(agoSeconds)));
    }

    private static LeaseRecovery.Decision decide(
            RunRecord record, Optional<CheckpointKind> checkpoint) {
        return LeaseRecovery.decide(record, checkpoint, TTL, T0);
    }

    @Nested
    @DisplayName("safe to replay")
    class SafeToReplay {

        @Test
        @DisplayName("a BEFORE_MODEL checkpoint past the grace period is requeued")
        void beforeModelRequeued() {
            // Expired 5 minutes ago: well past the 2-minute grace period.
            LeaseRecovery.Decision decision = decide(
                    record(TurnStatus.RUNNING, expired(300)), Optional.of(CheckpointKind.BEFORE_MODEL));

            LeaseRecovery.Decision.Requeue requeue =
                    assertInstanceOf(LeaseRecovery.Decision.Requeue.class, decision);
            assertEquals(CheckpointKind.BEFORE_MODEL, requeue.from());
        }

        @Test
        @DisplayName("BEFORE_BLOCK and BEFORE_CAPABILITY are also replay-safe")
        void otherSafeKinds() {
            assertInstanceOf(LeaseRecovery.Decision.Requeue.class,
                    decide(record(TurnStatus.RUNNING, expired(300)),
                            Optional.of(CheckpointKind.BEFORE_BLOCK)));
            assertInstanceOf(LeaseRecovery.Decision.Requeue.class,
                    decide(record(TurnStatus.RUNNING, expired(300)),
                            Optional.of(CheckpointKind.BEFORE_CAPABILITY)));
        }
    }

    @Nested
    @DisplayName("unsafe to replay - must fail terminally")
    class UnsafeToReplay {

        @Test
        @DisplayName("AFTER_CAPABILITY fails rather than repeating a side effect")
        void afterCapabilityFails() {
            // The capability already ran. A file may be written, a request already sent.
            LeaseRecovery.Decision decision = decide(
                    record(TurnStatus.RUNNING, expired(300)),
                    Optional.of(CheckpointKind.AFTER_CAPABILITY));

            LeaseRecovery.Decision.FailTerminal failed =
                    assertInstanceOf(LeaseRecovery.Decision.FailTerminal.class, decision);
            assertEquals(FailureKind.LEASE_EXPIRED, failed.kind());
            assertTrue(failed.reason().contains("AFTER_CAPABILITY"));
        }

        @Test
        @DisplayName("AFTER_MODEL fails: tokens were spent and the provider may have acted")
        void afterModelFails() {
            assertInstanceOf(LeaseRecovery.Decision.FailTerminal.class,
                    decide(record(TurnStatus.RUNNING, expired(300)),
                            Optional.of(CheckpointKind.AFTER_MODEL)));
        }

        @Test
        @DisplayName("an UNKNOWN checkpoint kind fails closed")
        void unknownFailsClosed() {
            assertInstanceOf(LeaseRecovery.Decision.FailTerminal.class,
                    decide(record(TurnStatus.RUNNING, expired(300)),
                            Optional.of(CheckpointKind.UNKNOWN)));
        }

        @Test
        @DisplayName("no checkpoint at all fails: there is no proven safe point")
        void noCheckpointFails() {
            LeaseRecovery.Decision.FailTerminal failed = assertInstanceOf(
                    LeaseRecovery.Decision.FailTerminal.class,
                    decide(record(TurnStatus.RUNNING, expired(300)), Optional.empty()));

            assertTrue(failed.reason().contains("no checkpoint"));
        }
    }

    @Nested
    @DisplayName("the grace period")
    class GracePeriod {

        @Test
        @DisplayName("a lease expired less than one TTL ago is left alone")
        void withinGraceLeftAlone() {
            // Expired 60s ago; the TTL is 120s, so a live-but-slow worker could still renew.
            LeaseRecovery.Decision decision = decide(
                    record(TurnStatus.RUNNING, expired(60)), Optional.of(CheckpointKind.BEFORE_MODEL));

            LeaseRecovery.Decision.LeaveAlone left =
                    assertInstanceOf(LeaseRecovery.Decision.LeaveAlone.class, decision);
            assertTrue(left.reason().contains("grace"));
        }

        @Test
        @DisplayName("exactly one full TTL past expiry crosses the threshold")
        void exactlyAtGraceBoundary() {
            // The boundary is inclusive: at expiry + TTL the worker has had a full renewal window.
            assertInstanceOf(LeaseRecovery.Decision.Requeue.class,
                    decide(record(TurnStatus.RUNNING, expired(TTL.toSeconds())),
                            Optional.of(CheckpointKind.BEFORE_MODEL)));
        }
    }

    @Nested
    @DisplayName("runs that are not candidates")
    class NotCandidates {

        @Test
        @DisplayName("a terminal run is never recovered")
        void terminalLeftAlone() {
            for (TurnStatus terminal : List.of(TurnStatus.COMPLETED, TurnStatus.FAILED, TurnStatus.CANCELLED)) {
                assertInstanceOf(LeaseRecovery.Decision.LeaveAlone.class,
                        decide(record(terminal, expired(3600)), Optional.of(CheckpointKind.BEFORE_MODEL)),
                        terminal + " must not be recovered");
            }
        }

        @Test
        @DisplayName("an unleased run is not a recovery candidate")
        void unleasedLeftAlone() {
            assertInstanceOf(LeaseRecovery.Decision.LeaveAlone.class,
                    decide(record(TurnStatus.QUEUED, Optional.empty()),
                            Optional.of(CheckpointKind.BEFORE_MODEL)));
        }

        @Test
        @DisplayName("a live lease is left alone")
        void liveLeaseLeftAlone() {
            Optional<Lease> live = Optional.of(new Lease("alive", T0.plusSeconds(60)));

            LeaseRecovery.Decision.LeaveAlone left = assertInstanceOf(
                    LeaseRecovery.Decision.LeaveAlone.class,
                    decide(record(TurnStatus.RUNNING, live), Optional.of(CheckpointKind.BEFORE_MODEL)));
            assertTrue(left.reason().contains("still valid"));
        }

        @Test
        @DisplayName("a run parked on a gate is waiting for a human, not a worker")
        void blockedLeftAlone() {
            // Its lease lapsing is expected and means nothing; recovering it would restart work
            // nobody has approved.
            LeaseRecovery.Decision.LeaveAlone left = assertInstanceOf(
                    LeaseRecovery.Decision.LeaveAlone.class,
                    decide(record(TurnStatus.BLOCKED_APPROVAL, expired(3600)),
                            Optional.of(CheckpointKind.BEFORE_BLOCK)));
            assertTrue(left.reason().contains("parked"));
        }
    }

    @Test
    @DisplayName("decisions are deterministic")
    void deterministic() {
        RunRecord record = record(TurnStatus.RUNNING, expired(300));

        assertEquals(
                decide(record, Optional.of(CheckpointKind.BEFORE_MODEL)),
                decide(record, Optional.of(CheckpointKind.BEFORE_MODEL)));
    }

}
