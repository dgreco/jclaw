package io.jclaw.app.cli;

import io.jclaw.app.runtime.RecoveryService;
import io.jclaw.app.runtime.RoutineRunner;
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
        description = "Poll for due routines and run them until interrupted.",
        mixinStandardHelpOptions = true)
public class WorkerCommand implements Callable<Integer> {

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(5);

    private final RoutineRunner runner;
    private final RecoveryService recovery;

    @Option(names = "--interval", description = "Seconds between polls. Default 30, minimum 5.")
    private int intervalSeconds = 30;

    @Option(names = "--once", description = "Poll a single time and exit. Useful for testing.")
    private boolean once;

    public WorkerCommand(RoutineRunner runner, RecoveryService recovery) {
        this.runner = runner;
        this.recovery = recovery;
    }

    @Override
    public Integer call() {
        Duration interval = Duration.ofSeconds(Math.max(MIN_INTERVAL.toSeconds(), intervalSeconds));
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread shutdown = new Thread(() -> stop.set(true), "jclaw-worker-stop");
        Runtime.getRuntime().addShutdownHook(shutdown);

        System.out.println("jclaw worker started (polling every " + interval.toSeconds() + "s). Ctrl-C to stop.");
        try {
            do {
                // Reconcile before claiming new work: a worker that starts while a predecessor's
                // runs are still leased would leave them stranded until something else swept.
                recovery.sweep(false).stream()
                        .filter(RecoveryService.Outcome::applied)
                        .forEach(outcome -> System.out.println("recovered " + outcome.decision().run().value()));

                List<RoutineRunner.Fired> fired = runner.runDue(stop);
                for (RoutineRunner.Fired entry : fired) {
                    System.out.printf("%s -> %s%n", entry.routine().name(), entry.result().status());
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
        System.out.println("jclaw worker stopped.");
        return 0;
    }
}
