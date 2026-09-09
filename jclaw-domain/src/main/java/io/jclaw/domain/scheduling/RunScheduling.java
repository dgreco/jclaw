package io.jclaw.domain.scheduling;

import io.jclaw.contracts.turn.RunStore.RunRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure selection of queued runs for a scheduler pass.
 *
 * <p>The judgement, which runs to start now given what is already in flight, is the part worth
 * testing exhaustively, and it needs no executor, no store, and no clock to test. The scheduler
 * in the application layer carries the decision out.
 *
 * <p>Two rules, in this order:
 * <ul>
 *   <li><b>One run per thread.</b> A thread that already has a run in flight gets nothing more
 *       this pass, and two queued runs on one thread are started one pass apart. The thread lock
 *       would refuse the second anyway; refusing here avoids claiming a lease only to give it
 *       back.</li>
 *   <li><b>Bounded concurrency.</b> At most {@code maxConcurrent} runs in flight across all
 *       threads, oldest submission first.</li>
 * </ul>
 *
 * <p>IronClaw's scheduler adds per-user and per-inbound-type caps. jclaw is single-user, and the
 * one inbound type is the CLI, so those collapse to the global cap; the shape leaves room to add
 * them as further filters here.
 */
public final class RunScheduling {

    private RunScheduling() {
    }

    /** Concurrency limits for one scheduler. */
    public record Caps(int maxConcurrent) {
        public Caps {
            if (maxConcurrent <= 0) {
                throw new IllegalArgumentException("maxConcurrent must be positive, got " + maxConcurrent);
            }
        }
    }

    /**
     * Chooses which queued runs to start.
     *
     * @param queued      runs in {@code QUEUED} status, any order
     * @param busyThreads lock keys of threads with a run in flight
     * @param inFlight    how many runs are executing now
     * @return runs to start, oldest first, never more than the free slots and never two on one
     *         thread
     */
    public static List<RunRecord> select(
            List<RunRecord> queued, Set<String> busyThreads, int inFlight, Caps caps) {

        Objects.requireNonNull(queued, "queued");
        Objects.requireNonNull(busyThreads, "busyThreads");
        Objects.requireNonNull(caps, "caps");
        if (inFlight < 0) {
            throw new IllegalArgumentException("inFlight must be non-negative");
        }

        int slots = caps.maxConcurrent() - inFlight;
        if (slots <= 0 || queued.isEmpty()) {
            return List.of();
        }

        Set<String> taken = new HashSet<>(busyThreads);
        List<RunRecord> chosen = new ArrayList<>();
        List<RunRecord> oldestFirst = queued.stream()
                .sorted(Comparator.comparing(RunRecord::submittedAt)
                        .thenComparing(record -> record.run().value()))
                .toList();
        for (RunRecord record : oldestFirst) {
            if (chosen.size() >= slots) {
                break;
            }
            String key = record.scope().lockKey();
            if (!taken.add(key)) {
                continue; // that thread is busy, or already chosen this pass
            }
            chosen.add(record);
        }
        return List.copyOf(chosen);
    }
}
