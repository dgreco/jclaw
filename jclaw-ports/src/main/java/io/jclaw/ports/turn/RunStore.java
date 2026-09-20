// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.turn;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable record of runs and the profile each was admitted under.
 *
 * <p>Exists so a parked run can be resumed by a <em>different process</em> without re-deriving its
 * settings from current configuration. IronClaw is explicit about this: the resolved run profile
 * is captured on the run so execution recovers without silently acquiring a different driver,
 * model, or policy after a restart. Re-resolving at resume time is how a run that was approved
 * under one model quietly continues under another.
 *
 * <p>Deliberately stores only what resume needs plus what {@code status} displays. Prompts, tool
 * arguments, and replies live in the transcript, not here.
 */
public interface RunStore {

    /**
     * A worker's claim on a run.
     *
     * <p>A lease is how the system distinguishes "a worker is executing this right now" from "a
     * worker died holding it". Both look identical in the run status alone, which is why the
     * expiry instant is recorded rather than inferred.
     *
     * @param workerId  who holds it; a heartbeat from anyone else must be refused, or two workers
     *                  can silently execute the same run
     * @param expiresAt when the claim lapses unless renewed
     */
    record Lease(String workerId, Instant expiresAt) {
        public Lease {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        public boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }

        public boolean heldBy(String candidate) {
            return workerId.equals(candidate);
        }
    }

    /**
     * One run's admitted profile and current lifecycle state.
     *
     * @param model        model id resolved at admission and replayed on resume
     * @param systemPrompt system prompt resolved at admission
     * @param lease        present while a worker holds a claim; absent when queued, parked, or
     *                     terminal
     */
    record RunRecord(
            TurnRunId run,
            TurnScope scope,
            TurnStatus status,
            String model,
            String systemPrompt,
            Instant submittedAt,
            Optional<Instant> finishedAt,
            Optional<Lease> lease) {

        public RunRecord {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(submittedAt, "submittedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
            Objects.requireNonNull(lease, "lease");
        }

        public boolean isResumable() {
            return status.isResumable();
        }

        /** Whether this run's lease has lapsed, making it a candidate for recovery. */
        public boolean hasExpiredLease(Instant now) {
            return lease.map(held -> held.isExpiredAt(now)).orElse(false);
        }
    }

    /** Records a newly admitted run. */
    void record(RunRecord record);

    /** Updates a run's lifecycle state. */
    void updateStatus(TurnRunId run, TurnStatus status);

    Optional<RunRecord> find(TurnRunId run);

    /** Runs in the given status, newest first. */
    List<RunRecord> byStatus(TurnStatus status, int limit);

    /**
     * Claims a run for a worker, or renews an existing claim.
     *
     * @return false when the run is already leased by a <em>different</em> worker, which is how
     *         two workers racing for the same run resolve without both proceeding
     */
    boolean claim(TurnRunId run, String workerId, Instant expiresAt);

    /**
     * Renews a claim.
     *
     * @return false when the lease was lost — taken over or cleared. A worker that gets false must
     *         stop: something else may already be executing the run.
     */
    boolean heartbeat(TurnRunId run, String workerId, Instant expiresAt);

    /** Releases a claim without changing the run's status. */
    void releaseLease(TurnRunId run);

    /** Runs whose lease has lapsed at {@code now}. The reconciler's input. */
    List<RunRecord> expiredLeases(Instant now);

    /** All runs, newest first. */
    List<RunRecord> recent(int limit);
}
