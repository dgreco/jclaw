// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.bootstrap.runtime.RoutineRunner;
import io.jclaw.ports.routine.RoutineStore;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.domain.cron.RoutineSchedule;
import io.jclaw.domain.trigger.Trigger;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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

        @Option(names = "--cron", description = "Five-field cron, e.g. '0 9 * * MON-FRI'.")
        private String cron;

        @Option(names = "--every", description = "A heartbeat interval instead of cron: 30m, 2h, 1d, or PT30M.")
        private String every;

        @Option(names = "--webhook", description = "Fire on POST /hooks/<name> with a bearer secret, printed once.")
        private boolean webhook;

        @Option(names = "--topic",
                description = "With --webhook: a name several routines may share. One authenticated "
                        + "POST to any of them fires every routine declaring the same topic.")
        private String topic;

        @Option(names = "--watch",
                description = "Fire when a file matching this glob changes under the workspace, "
                        + "e.g. 'src/**/*.java'. Checked on the worker's tick.")
        private String watch;

        @Option(names = "--on",
                description = "Fire on an audit event: run.finished, gate.raised, or turn.submitted "
                        + "(a message arriving on a thread, from anywhere).")
        private String on;

        @Option(names = "--when", description = "With --on: attribute filters, e.g. status=FAILED. Repeatable.")
        private String[] when = new String[0];

        @Option(names = "--zone", description = "IANA time zone. Defaults to the system zone.")
        private String zone = ZoneId.systemDefault().getId();

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
            // Validate the trigger before storing it: a routine that can never fire is a routine
            // whose absence nobody notices until they go looking for its output.
            int forms = (cron != null ? 1 : 0) + (every != null ? 1 : 0) + (webhook ? 1 : 0)
                    + (on != null ? 1 : 0) + (watch != null ? 1 : 0);
            if (forms != 1) {
                System.err.println("jclaw: give exactly one of --cron, --every, --webhook, --watch, --on");
                return 1;
            }
            if (topic != null && !webhook) {
                System.err.println("jclaw: --topic applies to --webhook routines only");
                return 1;
            }
            String secret = null;
            String expression;
            if (cron != null) {
                expression = cron;
            } else if (every != null) {
                expression = "every " + every;
            } else if (webhook) {
                byte[] bytes = new byte[24];
                new SecureRandom().nextBytes(bytes);
                secret = HexFormat.of().formatHex(bytes);
                expression = "webhook sha256:" + Trigger.hashSecret(secret)
                        + (topic == null || topic.isBlank() ? "" : " topic=" + topic.trim());
            } else if (watch != null) {
                expression = "watch " + watch;
            } else {
                expression = "on " + on + Arrays.stream(when).map(w -> " " + w).reduce("", String::concat);
            }
            var parsed = Trigger.parse(expression);
            if (parsed.isErr()) {
                System.err.println("jclaw: invalid trigger: " + parsed.errorAsOptional().orElse("?"));
                return 1;
            }
            try {
                ZoneId.of(zone);
            } catch (RuntimeException e) {
                System.err.println("jclaw: unknown time zone: " + zone);
                return 1;
            }

            RoutineStore.Routine routine = store.create(
                    runtime.scopeFor(SCOPE_THREAD),
                    name,
                    expression,
                    zone,
                    String.join(" ", prompt),
                    new ThreadId(thread == null
                            ? name.replaceAll("\\s+", "-").toLowerCase(Locale.ROOT)
                            : thread));

            if (!RoutineSchedule.canFire(routine)) {
                System.err.println("jclaw: that schedule can never fire; routine not usable: " + expression);
                store.delete(routine.id());
                return 1;
            }

            String form = switch (parsed.orElseThrow()) {
                case Trigger.Cron ignored -> "cron: " + expression;
                case Trigger.Heartbeat heartbeat -> "heartbeat: every " + heartbeat.interval();
                case Trigger.Webhook hook -> "webhook"
                        + (hook.topic().isEmpty() ? "" : ", topic " + hook.topic());
                case Trigger.Watch w -> "watch: " + w.glob();
                case Trigger.OnEvent ignored -> "event: " + expression.substring("on ".length());
            };
            System.out.println("Created " + routine.id().value() + " (" + form + ")");
            RoutineSchedule.nextFire(routine)
                    .ifPresent(next -> System.out.println("Next fire: " + next));
            if (secret != null) {
                System.out.println("Webhook: POST /hooks/" + name + " with 'Authorization: Bearer " + secret + "'");
                System.out.println("This secret is shown once; only its hash is stored.");
            }
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
                boolean due = RoutineSchedule.due(List.of(routine), Instant.now()).size() == 1;
                if (dueOnly && !due) {
                    continue;
                }
                System.out.printf("%s  %-20s %-18s %s%n",
                        routine.id().value(),
                        routine.name(),
                        Trigger.parse(routine.trigger()).toOptional()
                                .map(t -> t instanceof Trigger.Webhook hook
                                        ? "webhook" + (hook.topic().isEmpty() ? "" : " topic=" + hook.topic())
                                        : routine.trigger())
                                .orElse(routine.trigger()),
                        routine.enabled() ? (due ? "DUE" : "scheduled") : "paused");
                System.out.println("    prompt: " + routine.prompt());
                RoutineSchedule.nextFire(routine)
                        .ifPresentOrElse(
                                next -> System.out.println("    next:   " + next),
                                () -> System.out.println("    next:   " + (RoutineSchedule.canFire(routine)
                                        ? "(when its webhook or event arrives)" : "(never - unschedulable)")));
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
