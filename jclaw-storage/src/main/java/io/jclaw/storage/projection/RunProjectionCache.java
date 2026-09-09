package io.jclaw.storage.projection;

import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.projection.RunProjection;
import io.jclaw.domain.projection.RunProjection.RunView;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Materialised run projections: the fold, kept, for runs whose events can no longer change.
 *
 * <p>Folding is a total function over a run's events, so a projection has never needed a store —
 * which is what made it cheap to add and impossible to corrupt. It also means every
 * {@code status --run}, every {@code GET /runs/{id}}, and every OpenAI-compatible completion
 * reads and decodes the whole event log for that run. For a finished run with a hundred events
 * that is nothing; for one with ten thousand, on a page listing runs, it is the request.
 *
 * <p>So this caches, under one rule that keeps the guarantee intact: <strong>only terminal runs
 * are stored</strong>. A finished run's events are final, so its fold is final, and a cached row
 * cannot disagree with the log. A running or parked run is folded every time, as before. There
 * is no invalidation to get wrong because there is nothing that can go stale.
 *
 * <p>A miss, a row from an older codec version, and an unreadable row are all the same thing: a
 * fold. A cache that can fail a request is worse than no cache.
 */
public interface RunProjectionCache {

    /** The stored projection for a run, if one was kept and can still be read. */
    Optional<RunView> find(TurnRunId run);

    /** Stores a terminal run's projection. Implementations may ignore a non-terminal one. */
    void put(RunView view);

    /**
     * The projection for a run: from the cache when it is there, otherwise folded and — if the
     * run has finished — kept.
     *
     * @param events reads the run's events; called only on a miss, which is the saving
     */
    default RunView of(TurnRunId run, Supplier<List<JclawEvent>> events) {
        Optional<RunView> cached = find(run);
        if (cached.isPresent()) {
            return cached.get();
        }
        RunView folded = RunProjection.fold(run, events.get());
        if (folded.status().filter(TurnStatus::isTerminal).isPresent()) {
            put(folded);
        }
        return folded;
    }

    /** Keeps nothing and finds nothing: every projection is folded, as it always was. */
    static RunProjectionCache none() {
        return new RunProjectionCache() {
            @Override
            public Optional<RunView> find(TurnRunId run) {
                return Optional.empty();
            }

            @Override
            public void put(RunView view) {
            }
        };
    }
}
