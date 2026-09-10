// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.app.runtime.RecoveryService;
import io.jclaw.app.runtime.RetentionService;
import io.jclaw.app.runtime.RoutineRunner;
import io.jclaw.app.runtime.TurnRunScheduler;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-running poller that fires routines as they come due.
 *
 * <p>An alternative to putting {@code jclaw routines run-due} in system cron, for deployments
 * where a supervised process is easier to manage than a crontab. Both drive the same
 * {@link RoutineRunner}; neither is privileged.
 *
 * <p>Polling rather than sleeping until the next fire time is deliberate. Routines can be added,
 * paused, or removed by another process at any moment, so a worker that computed one long sleep
 * would keep sleeping through a routine created a second later.
 *
 * <p>Stops cleanly on interrupt: the flag is checked between turns, so a shutdown never lands in
 * the middle of a run.
 */
@Component
@Command(
        name = "worker",
        description = "Poll for due routines and queued runs, executing them until interrupted.",
        mixinStandardHelpOptions = true)
public class WorkerCommand implements Callable<Integer> {

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(5);

    private final RoutineRunner runner;
    private final io.jclaw.app.runtime.WatchTriggerScanner watches;
    private final RecoveryService recovery;
    private final TurnRunScheduler scheduler;
    private final RetentionService retention;

    /** Retention rewrites files; once an hour is plenty and keeps the sweep off the hot path. */
    private static final Duration RETENTION_INTERVAL = Duration.ofHours(1);

    @Option(names = "--concurrency", description = "Queued runs executed at once. Default 2.")
    private int concurrency = 2;

    @Option(names = "--interval", description = "Seconds between polls. Default 30, minimum 5.")
    private int intervalSeconds = 30;

    @Option(names = "--once", description = "Poll a single time and exit. Useful for testing.")
    private boolean once;

    public WorkerCommand(RoutineRunner runner, io.jclaw.app.runtime.WatchTriggerScanner watches,
            RecoveryService recovery, TurnRunScheduler scheduler,
                         RetentionService retention) {
        this.watches = watches;
        this.runner = runner;
        this.recovery = recovery;
        this.scheduler = scheduler;
        this.retention = retention;
    }

    @Override
    public Integer call() {
        Duration interval = Duration.ofSeconds(Math.max(MIN_INTERVAL.toSeconds(), intervalSeconds));
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread shutdown = new Thread(() -> stop.set(true), "jclaw-worker-stop");
        Runtime.getRuntime().addShutdownHook(shutdown);

        System.out.println("jclaw worker started (polling every " + interval.toSeconds() + "s). Ctrl-C to stop.");
        long lastRetention = 0;
        try {
            do {
                if (System.nanoTime() - lastRetention > RETENTION_INTERVAL.toNanos() || lastRetention == 0) {
                    retention.sweep(false).stream()
                            .filter(swept -> swept.dropped() > 0)
                            .forEach(swept -> System.out.printf("retention %s: dropped %d%n",
                                    swept.store(), swept.dropped()));
                    lastRetention = System.nanoTime();
                }
                // Reconcile before claiming new work: a worker that starts while a predecessor's
                // runs are still leased would leave them stranded until something else swept.
                recovery.sweep(false).stream()
                        .filter(RecoveryService.Outcome::applied)
                        .forEach(outcome -> System.out.println("recovered " + outcome.decision().run().value()));

                List<RoutineRunner.Fired> fired = runner.runDue(stop);
                for (RoutineRunner.Fired entry : fired) {
                    System.out.printf("%s -> %s%n", entry.routine().name(), entry.result().status());
                }

                // Watch routines: the same tick, since a file change is another kind of "due".
                for (var watched : watches.scan()) {
                    System.out.printf("%s -> queued %s (watch %s)%n",
                            watched.routine().name(), watched.run().value(), watched.glob());
                }

                // Queued work: submitted turns and runs requeued by recovery. Started here under
                // the concurrency cap; finished ones are reported on a later pass.
                scheduler.reapFinished().forEach(WorkerCommand::report);
                scheduler.tick(Math.max(1, concurrency), stop)
                        .forEach(run -> System.out.println("started " + run.value()));
                if (once) {
                    scheduler.drain().forEach(WorkerCommand::report);
                }
                if (once || stop.get()) {
                    break;
                }
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } while (!stop.get());
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException alreadyShuttingDown) {
                // Shutdown in progress; nothing to remove.
            }
        }
        scheduler.drain().forEach(WorkerCommand::report);
        System.out.println("jclaw worker stopped.");
        return 0;
    }

    private static void report(TurnRunScheduler.Outcome outcome) {
        System.out.printf("%s -> %s%s%n", outcome.run().value(), outcome.result().status(),
                outcome.result().failureDetail().map(d -> " (" + d + ")").orElse(""));
    }
}
