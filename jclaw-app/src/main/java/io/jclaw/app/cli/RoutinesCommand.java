package io.jclaw.app.cli;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.app.runtime.RoutineRunner;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.domain.cron.CronSpec;
import io.jclaw.domain.cron.RoutineSchedule;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages scheduled routines.
 *
 * <p>{@code run-due} is the important one: it fires everything due and exits, which makes system
 * cron the scheduler. That is a deliberate choice over embedding one — the operating system
 * already has a scheduler that survives reboots, logs, and is understood by every operator, and
 * reimplementing it inside a CLI buys nothing.
 *
 * <pre>
 * # in crontab
 * * * * * * jclaw routines run-due --jclaw.workspace=/path/to/project
 * </pre>
 */
@Component
@Command(
        name = "routines",
        description = "Create and run scheduled agent routines.",
        mixinStandardHelpOptions = true,
        subcommands = {
                RoutinesCommand.Add.class,
                RoutinesCommand.ListRoutines.class,
                RoutinesCommand.Remove.class,
                RoutinesCommand.Pause.class,
                RoutinesCommand.Resume.class,
                RoutinesCommand.RunDue.class
        })
public class RoutinesCommand implements Runnable {

    /** Routines are project-scoped; any thread resolves the same project. */
    static final ThreadId SCOPE_THREAD = new ThreadId("routines");

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Component
    @Command(name = "add", description = "Create a routine.", mixinStandardHelpOptions = true)
    public static class Add implements Callable<Integer> {

        private final RoutineStore store;
        private final JclawRuntime runtime;

        @Option(names = "--name", required = true, description = "Human-readable name.")
        private String name;

        @Option(names = "--cron", required = true,
                description = "Five-field cron, e.g. '0 9 * * MON-FRI'.")
        private String cron;

        @Option(names = "--zone", description = "IANA time zone. Defaults to the system zone.")
        private String zone = java.time.ZoneId.systemDefault().getId();

        @Option(names = "--thread", description = "Thread the routine runs in. Defaults to its name.")
        private String thread;

        @Parameters(arity = "1..*", description = "The prompt to run on each firing.")
        private String[] prompt;

        public Add(RoutineStore store, JclawRuntime runtime) {
            this.store = store;
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            // Validate the schedule before storing it: a routine that can never fire is a routine
            // whose absence nobody notices until they go looking for its output.
            try {
                CronSpec.parse(cron);
            } catch (IllegalArgumentException e) {
                System.err.println("jclaw: invalid cron expression: " + e.getMessage());
                return 1;
            }
            try {
                java.time.ZoneId.of(zone);
            } catch (RuntimeException e) {
                System.err.println("jclaw: unknown time zone: " + zone);
                return 1;
            }

            RoutineStore.Routine routine = store.create(
                    runtime.scopeFor(SCOPE_THREAD),
                    name,
                    cron,
                    zone,
                    String.join(" ", prompt),
                    new ThreadId(thread == null ? name.replaceAll("\\s+", "-").toLowerCase() : thread));

            if (!RoutineSchedule.isSchedulable(routine)) {
                System.err.println("jclaw: that schedule can never fire; routine not usable: " + cron);
                store.delete(routine.id());
                return 1;
            }

            System.out.println("Created " + routine.id().value());
            RoutineSchedule.nextFire(routine)
                    .ifPresent(next -> System.out.println("Next fire: " + next));
            return 0;
        }
    }

    @Component
    @Command(name = "list", description = "List routines.", mixinStandardHelpOptions = true)
    public static class ListRoutines implements Callable<Integer> {

        private final RoutineStore store;
        private final JclawRuntime runtime;

        @Option(names = "--due", description = "Show only routines currently due.")
        private boolean dueOnly;

        public ListRoutines(RoutineStore store, JclawRuntime runtime) {
            this.store = store;
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            List<RoutineStore.Routine> routines = store.list(runtime.scopeFor(SCOPE_THREAD));
            if (routines.isEmpty()) {
                System.out.println("(no routines)");
                return 0;
            }
            for (RoutineStore.Routine routine : routines) {
                boolean due = RoutineSchedule.due(List.of(routine), java.time.Instant.now()).size() == 1;
                if (dueOnly && !due) {
                    continue;
                }
                System.out.printf("%s  %-20s %-18s %s%n",
                        routine.id().value(),
                        routine.name(),
                        routine.cronExpression(),
                        routine.enabled() ? (due ? "DUE" : "scheduled") : "paused");
                System.out.println("    prompt: " + routine.prompt());
                RoutineSchedule.nextFire(routine)
                        .ifPresentOrElse(
                                next -> System.out.println("    next:   " + next),
                                () -> System.out.println("    next:   (never - unschedulable)"));
            }
            return 0;
        }
    }

    @Component
    @Command(name = "remove", description = "Delete a routine.", mixinStandardHelpOptions = true)
    public static class Remove implements Callable<Integer> {
        private final RoutineStore store;

        @Parameters(index = "0", description = "Routine id.")
        private String id;

        public Remove(RoutineStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            return toggle(store.delete(new RoutineStore.RoutineId(id)), id, "Removed");
        }
    }

    @Component
    @Command(name = "pause", description = "Stop a routine firing.", mixinStandardHelpOptions = true)
    public static class Pause implements Callable<Integer> {
        private final RoutineStore store;

        @Parameters(index = "0", description = "Routine id.")
        private String id;

        public Pause(RoutineStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            return toggle(store.setEnabled(new RoutineStore.RoutineId(id), false), id, "Paused");
        }
    }

    @Component
    @Command(name = "resume", description = "Let a paused routine fire again.",
            mixinStandardHelpOptions = true)
    public static class Resume implements Callable<Integer> {
        private final RoutineStore store;

        @Parameters(index = "0", description = "Routine id.")
        private String id;

        public Resume(RoutineStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            return toggle(store.setEnabled(new RoutineStore.RoutineId(id), true), id, "Resumed");
        }
    }

    /** Fires everything due, then exits. Designed to be driven by system cron. */
    @Component
    @Command(name = "run-due", description = "Run every routine that is due, then exit.",
            mixinStandardHelpOptions = true)
    public static class RunDue implements Callable<Integer> {

        private final RoutineRunner runner;

        @Option(names = "--dry-run", description = "Show what would fire without running it.")
        private boolean dryRun;

        public RunDue(RoutineRunner runner) {
            this.runner = runner;
        }

        @Override
        public Integer call() {
            if (dryRun) {
                List<RoutineSchedule.Due> due = runner.peekDue();
                if (due.isEmpty()) {
                    System.out.println("(nothing due)");
                    return 0;
                }
                due.forEach(candidate -> System.out.println(
                        "would fire " + candidate.routine().name()
                                + " (scheduled " + candidate.scheduledFor() + ")"));
                return 0;
            }

            List<RoutineRunner.Fired> fired = runner.runDue(new AtomicBoolean(false));
            if (fired.isEmpty()) {
                System.out.println("(nothing due)");
                return 0;
            }
            int failures = 0;
            for (RoutineRunner.Fired entry : fired) {
                System.out.printf("%s -> %s%n", entry.routine().name(), entry.result().status());
                entry.result().reply().ifPresent(reply -> System.out.println("  " + reply));
                if (!entry.result().isSuccess()) {
                    failures++;
                }
            }
            // Non-zero when any routine failed, so cron surfaces it rather than swallowing it.
            return failures == 0 ? 0 : 1;
        }
    }

    private static int toggle(boolean existed, String id, String verb) {
        if (!existed) {
            System.err.println("jclaw: no such routine: " + id);
            return 1;
        }
        System.out.println(verb + " " + id);
        return 0;
    }
}
