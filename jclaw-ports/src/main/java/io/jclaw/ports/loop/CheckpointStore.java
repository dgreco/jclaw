// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.loop;

import io.jclaw.ports.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.ports.turn.TurnRunId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists resumable loop state and mints checkpoint refs.
 *
 * <p>The payload is opaque bytes to this store — the loop owns its own state schema, and the store
 * owns the metadata that recovery depends on. That split matters: the reconciler must be able to
 * decide whether an expired lease is safe to requeue by reading {@link Checkpoint#kind()}
 * <em>without</em> deserializing loop-owned state it may no longer understand after an upgrade.
 */
public interface CheckpointStore {

    /**
     * A persisted checkpoint.
     *
     * @param schemaVersion loop state schema version, so a resume can refuse state it cannot read
     *                      rather than misinterpreting it
     */
    record Checkpoint(
            LoopCheckpointStateRef ref,
            TurnRunId run,
            CheckpointKind kind,
            int iteration,
            int schemaVersion,
            byte[] payload,
            Instant writtenAt) {

        public Checkpoint {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(writtenAt, "writtenAt");
            payload = payload.clone(); // defensive: arrays are mutable
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        /** Whether resuming from this checkpoint can repeat no externally visible effect. */
        public boolean isReplaySafe() {
            return kind.replaysNoSideEffect();
        }
    }

    /** Writes a checkpoint and mints its ref. */
    LoopCheckpointStateRef write(
            TurnRunId run, CheckpointKind kind, int iteration, int schemaVersion, byte[] payload);

    /** Resolves a ref. Empty means the ref is not evidence of anything. */
    Optional<Checkpoint> resolve(LoopCheckpointStateRef ref);

    /** The most recent checkpoint for a run, used by lease recovery. */
    Optional<Checkpoint> latestFor(TurnRunId run);
}
