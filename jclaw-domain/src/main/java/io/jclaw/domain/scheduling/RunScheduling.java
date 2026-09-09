package io.jclaw.domain.scheduling;

import io.jclaw.contracts.turn.RunStore.RunRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * <p>A third rule, IronClaw's per-user cap: at most {@code maxPerTenant} runs in flight for any
 * one tenant, so one busy user cannot take every slot from the others. The CLI's {@code local}
 * tenant and each HTTP user count separately.
 */
public final class RunScheduling {

    private RunScheduling() {
    }

    /** Concurrency limits for one scheduler. */
    public record Caps(int maxConcurrent, int maxPerTenant) {
        public Caps {
            if (maxConcurrent <= 0) {
                throw new IllegalArgumentException("maxConcurrent must be positive, got " + maxConcurrent);
            }
            if (maxPerTenant <= 0) {
                throw new IllegalArgumentException("maxPerTenant must be positive, got " + maxPerTenant);
            }
        }

        /** No per-tenant cap beyond the global one. */
        public Caps(int maxConcurrent) {
            this(maxConcurrent, maxConcurrent);
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
        return select(queued, busyThreads, inFlight, Map.of(), caps);
    }

    /**
     * Chooses which queued runs to start, honouring the per-tenant cap.
     *
     * @param inFlightByTenant how many runs each tenant has executing now
     */
    public static List<RunRecord> select(
            List<RunRecord> queued, Set<String> busyThreads, int inFlight,
            Map<String, Integer> inFlightByTenant, Caps caps) {

        Objects.requireNonNull(queued, "queued");
        Objects.requireNonNull(busyThreads, "busyThreads");
        Objects.requireNonNull(inFlightByTenant, "inFlightByTenant");
        Objects.requireNonNull(caps, "caps");
        if (inFlight < 0) {
            throw new IllegalArgumentException("inFlight must be non-negative");
        }

        int slots = caps.maxConcurrent() - inFlight;
        if (slots <= 0 || queued.isEmpty()) {
            return List.of();
        }

        Set<String> taken = new HashSet<>(busyThreads);
        Map<String, Integer> perTenant = new HashMap<>(inFlightByTenant);
        List<RunRecord> chosen = new ArrayList<>();
        List<RunRecord> oldestFirst = queued.stream()
                .sorted(Comparator.comparing(RunRecord::submittedAt)
                        .thenComparing(record -> record.run().value()))
                .toList();
        for (RunRecord record : oldestFirst) {
            if (chosen.size() >= slots) {
                break;
            }
            String tenant = record.scope().tenant();
            if (perTenant.getOrDefault(tenant, 0) >= caps.maxPerTenant()) {
                continue; // this tenant has its share; the slot goes to another
            }
            String key = record.scope().lockKey();
            if (!taken.add(key)) {
                continue; // that thread is busy, or already chosen this pass
            }
            perTenant.merge(tenant, 1, Integer::sum);
            chosen.add(record);
        }
        return List.copyOf(chosen);
    }
}
