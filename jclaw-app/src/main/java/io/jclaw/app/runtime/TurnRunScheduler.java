package io.jclaw.app.runtime;

import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.scheduling.RunScheduling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Claims queued runs and executes them with bounded concurrency.
 *
 * <p>The scheduler IronClaw's {@code TurnRunScheduler} provides: durable queued work, picked up by
 * a worker under a concurrency cap, one run per thread at a time. Everything it relies on already
 * existed as primitives: leases, heartbeats, the thread lock, and lease recovery. This class only
 * decides <em>when</em>, and it delegates the decision itself to the pure
 * {@link RunScheduling}.
 *
 * <p>Work enters the queue two ways. {@code jclaw submit} enqueues a turn without executing it,
 * which is the shape a WebUI or a channel adapter needs: accept the inbound message durably, reply
 * to the caller immediately, let a worker do the turn. And {@code recover} requeues runs whose
 * worker died at a replay-safe checkpoint; with a scheduler running they are picked up rather than
 * waiting for a human's {@code resume}.
 *
 * <p>Execution goes through {@link JclawRuntime#resume}, which takes the thread lock and the
 * lease. A run refused because another process holds its thread stays queued and is retried on a
 * later pass; nothing here marks a run failed for contention.
 */
@Service
public class TurnRunScheduler {

    private static final Logger log = LoggerFactory.getLogger(TurnRunScheduler.class);

    private final RunStore runs;
    private final JclawRuntime runtime;
    private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "jclaw-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    /** In-flight runs keyed by thread lock key; the value is the task executing the run. */
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

    private record InFlight(TurnRunId run, Future<JclawRuntime.TurnResult> future) {
    }

    /** The result of one run the scheduler executed. */
    public record Outcome(TurnRunId run, JclawRuntime.TurnResult result) {
    }

    public TurnRunScheduler(RunStore runs, JclawRuntime runtime) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * One scheduling pass: reap finished runs, then start whatever the caps allow.
     *
     * @return the runs started by this pass
     */
    public synchronized List<TurnRunId> tick(int maxConcurrent, AtomicBoolean cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        reapFinished();

        List<RunStore.RunRecord> queued = runs.byStatus(TurnStatus.QUEUED, Integer.MAX_VALUE);
        List<RunStore.RunRecord> chosen = RunScheduling.select(
                queued, Set.copyOf(inFlight.keySet()), inFlight.size(),
                new RunScheduling.Caps(maxConcurrent));

        List<TurnRunId> started = new ArrayList<>();
        for (RunStore.RunRecord record : chosen) {
            String key = record.scope().lockKey();
            TurnRunId run = record.run();
            log.debug("scheduler: starting run {} on thread {} ({} in flight)",
                    run.value(), record.scope().thread().value(), inFlight.size());
            Future<JclawRuntime.TurnResult> future =
                    executor.submit(() -> runtime.resume(run, cancelled));
            inFlight.put(key, new InFlight(run, future));
            started.add(run);
        }
        return List.copyOf(started);
    }

    /** Runs that have finished since the last call, with their results. */
    public synchronized List<Outcome> reapFinished() {
        List<Outcome> finished = new ArrayList<>();
        inFlight.entrySet().removeIf(entry -> {
            Future<JclawRuntime.TurnResult> future = entry.getValue().future();
            if (!future.isDone()) {
                return false;
            }
            finished.add(new Outcome(entry.getValue().run(), resultOf(future)));
            return true;
        });
        finished.forEach(this::requeueWaitingParent);
        return List.copyOf(finished);
    }

    /**
     * A finished child run wakes the parent parked on it.
     *
     * <p>The parent is found by thread: a subagent thread names its parent, and the parent run is
     * whichever run on that thread is {@code WAITING_PROCESS}. It goes back to {@code QUEUED} and
     * the next pass resumes it, whereupon it re-dispatches the subagent call and gets the child's
     * conclusion. Nothing here inspects the child's result; the tool does that.
     */
    private void requeueWaitingParent(Outcome child) {
        runs.find(child.run())
                .flatMap(record -> RuntimeSubagentHost.parentOf(record.scope().thread()))
                .ifPresent(parentThread -> runs.byStatus(TurnStatus.WAITING_PROCESS, Integer.MAX_VALUE).stream()
                        .filter(parent -> parent.scope().thread().equals(parentThread))
                        .forEach(parent -> {
                            try {
                                runs.updateStatus(parent.run(), TurnStatus.QUEUED);
                                log.debug("scheduler: child {} finished; parent {} requeued",
                                        child.run().value(), parent.run().value());
                            } catch (IllegalStateException raced) {
                                // Someone else moved it first; the lifecycle guard is doing its job.
                            }
                        }));
    }

    /** Waits for every in-flight run to finish and returns their results. */
    public List<Outcome> drain() {
        List<InFlight> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(inFlight.values());
        }
        for (InFlight entry : snapshot) {
            try {
                entry.future().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ignored) {
                // Reported through resultOf below.
            }
        }
        return reapFinished();
    }

    /** A single pass run to completion: start what the caps allow, wait for all of it. */
    public List<Outcome> runOnce(int maxConcurrent, AtomicBoolean cancelled) {
        tick(maxConcurrent, cancelled);
        return drain();
    }

    public synchronized int inFlight() {
        return inFlight.size();
    }

    private static JclawRuntime.TurnResult resultOf(Future<JclawRuntime.TurnResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while collecting a run result", e);
        } catch (ExecutionException e) {
            // A runtime that throws is a defect; surface it rather than lose the run silently.
            throw new IllegalStateException("run execution threw", e.getCause());
        }
    }
}
