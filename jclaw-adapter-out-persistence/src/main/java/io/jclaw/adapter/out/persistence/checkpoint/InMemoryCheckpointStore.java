// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.checkpoint;

import io.jclaw.ports.loop.CheckpointKind;
import io.jclaw.ports.loop.CheckpointStore;
import io.jclaw.ports.turn.Ident;
import io.jclaw.ports.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.ports.turn.TurnRunId;

import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link CheckpointStore}.
 *
 * <p>Adequate for a single-process CLI, where a crash ends the process that would have resumed.
 * It is <em>not</em> adequate for a hosted deployment: there, lease recovery depends on a second
 * worker reading a checkpoint the first worker wrote, which requires durable storage. The port
 * exists precisely so that substitution is a wiring change.
 *
 * <p>Stated plainly rather than left implicit, because "resumable" quietly meaning "resumable
 * within one process" is the kind of assumption that fails in production.
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, Checkpoint> byRef = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryCheckpointStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public LoopCheckpointStateRef write(
            TurnRunId run, CheckpointKind kind, int iteration, int schemaVersion, byte[] payload) {

        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");

        LoopCheckpointStateRef ref = new LoopCheckpointStateRef(Ident.fresh("ckpt"));
        byRef.put(ref.value(), new Checkpoint(
                ref, run, kind, iteration, schemaVersion, payload, clock.instant()));
        return ref;
    }

    @Override
    public Optional<Checkpoint> resolve(LoopCheckpointStateRef ref) {
        Objects.requireNonNull(ref, "ref");
        return Optional.ofNullable(byRef.get(ref.value()));
    }

    @Override
    public Optional<Checkpoint> latestFor(TurnRunId run) {
        Objects.requireNonNull(run, "run");
        return byRef.values().stream()
                .filter(checkpoint -> checkpoint.run().equals(run))
                .max(Comparator.comparing(Checkpoint::writtenAt));
    }

    /** Number of stored checkpoints. Exposed for diagnostics and tests. */
    public int size() {
        return byRef.size();
    }
}
