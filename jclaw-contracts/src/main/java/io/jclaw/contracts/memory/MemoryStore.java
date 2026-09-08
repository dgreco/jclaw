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

    /** Stores a memory and mints its id. */
    MemoryId write(TurnScope scope, String text, List<String> tags);

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
