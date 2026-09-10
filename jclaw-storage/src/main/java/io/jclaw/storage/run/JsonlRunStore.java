// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.run;

import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.storage.rows.RowStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable {@link RunStore} over an append-only JSONL file.
 *
 * <p>Like the approval store, state is the fold of an append-only log: admission writes a
 * {@code submitted} record and each transition appends a {@code status} record. Nothing is
 * rewritten, so the full lifecycle of a run stays inspectable — which is what makes
 * {@code jclaw status} an audit view rather than a snapshot.
 *
 * <p>Transitions are validated against {@link TurnStatus#canTransitionTo}. A replayed or
 * out-of-order transition is rejected rather than written, so a terminal run cannot be resurrected
 * by a late-arriving update.
 */
public final class JsonlRunStore implements RunStore {

    private static final String KIND_SUBMITTED = "submitted";
    private static final String KIND_STATUS = "status";
    private static final String KIND_LEASE = "lease";
    private static final String KIND_RELEASE = "release";

    private final RowStore file;
    private final Clock clock;

    public JsonlRunStore(RowStore file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void record(RunRecord record) {
        Objects.requireNonNull(record, "record");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_SUBMITTED);
        row.put("run", record.run().value());
        row.put("tenant", record.scope().tenant());
        row.put("agent", record.scope().agent());
        row.put("project", record.scope().project());
        row.put("thread", record.scope().thread().value());
        row.put("status", record.status().name());
        row.put("model", record.model());
        row.put("systemPrompt", record.systemPrompt());
        row.put("submittedAt", record.submittedAt().toString());
        file.append(row);
    }

    @Override
    public void updateStatus(TurnRunId run, TurnStatus status) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(status, "status");

        Optional<RunRecord> current = find(run);
        if (current.isEmpty()) {
            return; // nothing to transition
        }
        TurnStatus from = current.get().status();
        if (from == status) {
            return; // idempotent no-op
        }
        if (!from.canTransitionTo(status)) {
            // Writing this would corrupt the lifecycle; a terminal run must stay terminal.
            throw new IllegalStateException(
                    "illegal run transition " + from + " -> " + status + " for " + run.value());
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_STATUS);
        row.put("run", run.value());
        row.put("status", status.name());
        row.put("at", clock.instant().toString());
        file.append(row);
    }

    @Override
    public boolean claim(TurnRunId run, String workerId, Instant expiresAt) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(expiresAt, "expiresAt");

        Optional<RunRecord> current = find(run);
        if (current.isEmpty()) {
            return false;
        }
        Optional<Lease> existing = current.get().lease();
        if (existing.isPresent()
                && !existing.get().heldBy(workerId)
                && !existing.get().isExpiredAt(clock.instant())) {
            // Someone else holds a live claim. Refusing here is what stops two workers from
            // executing the same run after a race.
            return false;
        }
        writeLease(run, workerId, expiresAt);
        return true;
    }

    @Override
    public boolean heartbeat(TurnRunId run, String workerId, Instant expiresAt) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(workerId, "workerId");

        Optional<Lease> existing = find(run).flatMap(RunRecord::lease);
        if (existing.isEmpty() || !existing.get().heldBy(workerId)) {
            // The lease was taken over or cleared. The caller must stop rather than keep working
            // on a run something else may now own.
            return false;
        }
        writeLease(run, workerId, expiresAt);
        return true;
    }

    @Override
    public void releaseLease(TurnRunId run) {
        Objects.requireNonNull(run, "run");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_RELEASE);
        row.put("run", run.value());
        row.put("at", clock.instant().toString());
        file.append(row);
    }

    @Override
    public List<RunRecord> expiredLeases(Instant now) {
        Objects.requireNonNull(now, "now");
        return replay().values().stream()
                .filter(record -> record.hasExpiredLease(now))
                .sorted(Comparator.comparing(RunRecord::submittedAt))
                .toList();
    }

    private void writeLease(TurnRunId run, String workerId, Instant expiresAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_LEASE);
        row.put("run", run.value());
        row.put("workerId", workerId);
        row.put("expiresAt", expiresAt.toString());
        file.append(row);
    }

    @Override
    public Optional<RunRecord> find(TurnRunId run) {
        Objects.requireNonNull(run, "run");
        return Optional.ofNullable(replay().get(run.value()));
    }

    @Override
    public List<RunRecord> byStatus(TurnStatus status, int limit) {
        Objects.requireNonNull(status, "status");
        return recent(Integer.MAX_VALUE).stream()
                .filter(record -> record.status() == status)
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<RunRecord> recent(int limit) {
        return replay().values().stream()
                .sorted(Comparator.comparing(RunRecord::submittedAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    /** Every run currently parked on a gate, newest first. */
    public List<RunRecord> resumable() {
        return recent(Integer.MAX_VALUE).stream()
                .filter(RunRecord::isResumable)
                .toList();
    }

    private Map<String, RunRecord> replay() {
        Map<String, RunRecord> runs = new LinkedHashMap<>();
        for (Map<String, Object> row : file.readAll()) {
            try {
                String kind = String.valueOf(row.get("kind"));
                String run = String.valueOf(row.get("run"));
                if (KIND_SUBMITTED.equals(kind)) {
                    runs.put(run, toRecord(row, run));
                } else if (KIND_STATUS.equals(kind)) {
                    RunRecord existing = runs.get(run);
                    if (existing != null) {
                        TurnStatus status = TurnStatus.valueOf(String.valueOf(row.get("status")));
                        Instant at = Instant.parse(String.valueOf(row.get("at")));
                        runs.put(run, withStatus(existing, status, at));
                    }
                } else if (KIND_LEASE.equals(kind)) {
                    RunRecord existing = runs.get(run);
                    if (existing != null) {
                        runs.put(run, withLease(existing, Optional.of(new Lease(
                                String.valueOf(row.get("workerId")),
                                Instant.parse(String.valueOf(row.get("expiresAt")))))));
                    }
                } else if (KIND_RELEASE.equals(kind)) {
                    RunRecord existing = runs.get(run);
                    if (existing != null) {
                        runs.put(run, withLease(existing, Optional.empty()));
                    }
                }
            } catch (RuntimeException e) {
                // A damaged line loses one run, not the whole history.
            }
        }
        return runs;
    }

    private static RunRecord toRecord(Map<String, Object> row, String run) {
        return new RunRecord(
                new TurnRunId(run),
                new TurnScope(
                        String.valueOf(row.get("tenant")),
                        String.valueOf(row.get("agent")),
                        String.valueOf(row.get("project")),
                        new ThreadId(String.valueOf(row.get("thread")))),
                TurnStatus.valueOf(String.valueOf(row.get("status"))),
                String.valueOf(row.get("model")),
                String.valueOf(row.get("systemPrompt")),
                Instant.parse(String.valueOf(row.get("submittedAt"))),
                Optional.empty(),
                Optional.empty());
    }

    private static RunRecord withStatus(RunRecord record, TurnStatus status, Instant at) {
        return new RunRecord(
                record.run(), record.scope(), status, record.model(), record.systemPrompt(),
                record.submittedAt(),
                status.isTerminal() ? Optional.of(at) : Optional.empty(),
                // A terminal run holds nothing: the lease is dropped so the reconciler never
                // considers a finished run recoverable.
                status.isTerminal() ? Optional.empty() : record.lease());
    }

    private static RunRecord withLease(RunRecord record, Optional<Lease> lease) {
        return new RunRecord(
                record.run(), record.scope(), record.status(), record.model(), record.systemPrompt(),
                record.submittedAt(), record.finishedAt(), lease);
    }
}
