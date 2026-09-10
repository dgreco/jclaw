// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.memory;

import io.jclaw.contracts.memory.MemoryRecord.MemoryId;
import io.jclaw.contracts.turn.TurnScope;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for memories. Deliberately dumb.
 *
 * <p>It stores, fetches, and deletes; it does not rank. Ranking is a pure function over records
 * and lives in {@code jclaw-domain}, so retrieval quality can be tested with plain values and
 * changed without touching persistence — and so a future SQL or vector-backed store inherits the
 * same ranking rather than reimplementing it in a query.
 */
public interface MemoryStore {

    /** Stores a memory without an embedding and mints its id. */
    default MemoryId write(TurnScope scope, String text, List<String> tags) {
        return write(scope, text, tags, Optional.empty());
    }

    /**
     * Stores a memory, with its embedding when the caller obtained one, and mints its id.
     *
     * <p>The store does not embed. Calling the embedding provider is the caller's job, so the
     * store never holds a network dependency and a store test never needs a fake model.
     */
    MemoryId write(TurnScope scope, String text, List<String> tags, Optional<Embedding> embedding);

    /**
     * Attaches or replaces the embedding of an existing memory. Returns whether it existed.
     *
     * <p>How memories written before embeddings were configured, or under a previous embedding
     * model, join the vector ranking.
     */
    boolean attachEmbedding(MemoryId id, Embedding embedding);

    Optional<MemoryRecord> find(MemoryId id);

    /**
     * Every memory in a scope, newest first.
     *
     * <p>Returning the full set is fine at CLI scale and keeps ranking pure. A store facing a
     * corpus large enough for this to hurt should push a coarse pre-filter down and let the domain
     * rank what survives.
     */
    List<MemoryRecord> all(TurnScope scope);

    /** Removes a memory. Returns whether it existed. */
    boolean delete(MemoryId id);

    /** Total memories in a scope. */
    int count(TurnScope scope);
}
