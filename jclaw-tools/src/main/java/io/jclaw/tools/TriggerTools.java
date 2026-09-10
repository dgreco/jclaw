// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.tools;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.HandlerError;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.domain.cron.RoutineSchedule;
import io.jclaw.domain.trigger.Trigger;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Lets the agent inspect and manage its own scheduled work, mirroring IronClaw's
 * {@code builtin.trigger_*} capabilities.
 *
 * <p><b>Creating a trigger is the most consequential thing in this file, and it is classed
 * accordingly.</b> A schedule is not a local write — it is the agent granting itself future
 * unattended execution. Whatever a human approves once will then run repeatedly with nobody
 * watching, which makes it a strictly larger grant than any single tool call. So
 * {@code trigger_create} is {@link EffectClass#PROCESS}: under the default policy it asks first,
 * and the human sees the cron expression and the prompt before agreeing to either.
 *
 * <p>Listing is a read and runs freely.
 */
public final class TriggerTools {

    private TriggerTools() {
    }

    public static List<CapabilityHandler> all(RoutineStore store) {
        return List.of(new Create(store), new ListTriggers(store), new SetEnabled(store, true),
                new SetEnabled(store, false), new Remove(store));
    }

    /** Schedules a recurring prompt. */
    public static final class Create implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "trigger_create",
                "Schedule a prompt to run on a recurring cron schedule. This grants future "
                        + "unattended execution, so it requires approval.",
                EffectClass.PROCESS,
                Schemas.object(
                        Schemas.properties(
                                "name", Schemas.string("Short name for the schedule."),
                                "cron", Schemas.string("Five-field cron, e.g. '0 9 * * MON-FRI'. Give this or 'every'."),
                                "every", Schemas.string("An interval instead of cron, e.g. '30m', '2h', '1d'."),
                                "prompt", Schemas.string("The prompt to run on each firing."),
                                "zone", Schemas.string("IANA time zone. Defaults to UTC.")),
                        List.of("name", "prompt")));

        private final RoutineStore store;

        public Create(RoutineStore store) {
            this.store = Objects.requireNonNull(store, "store");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            String name = invocation.stringArg("name", "");
            String cron = invocation.stringArg("cron", "");
            String every = invocation.stringArg("every", "");
            String prompt = invocation.stringArg("prompt", "");
            String zone = invocation.stringArg("zone", "UTC");

            if (name.isBlank() || prompt.isBlank() || (cron.isBlank() == every.isBlank())) {
                return Result.err(HandlerError.failed("name_prompt_and_one_of_cron_or_every_required"));
            }
            // The agent may create time-driven triggers only. Webhooks, watches, and event
            // triggers are the operator's: one grants an outside caller a way in, the others
            // react to the world outside the turn, and none should be something a model can set
            // up for itself. `timeDriven()` is the whole check, so a trigger kind added later is
            // excluded by default rather than by someone remembering to exclude it.
            String expression = cron.isBlank() ? "every " + every : cron;
            Trigger trigger = Trigger.parse(expression).toOptional().orElse(null);
            if (trigger == null || !trigger.timeDriven()) {
                return Result.err(HandlerError.failed("invalid_schedule"));
            }
            try {
                ZoneId.of(zone);
            } catch (RuntimeException e) {
                return Result.err(HandlerError.failed("invalid_schedule"));
            }

            RoutineStore.Routine routine = store.create(
                    invocation.scope(), name, expression, zone, prompt,
                    new ThreadId(name.replaceAll("\\s+", "-").toLowerCase(Locale.ROOT)));

            if (!RoutineSchedule.canFire(routine)) {
                // A schedule that can never match would sit there looking configured forever.
                store.delete(routine.id());
                return Result.err(HandlerError.failed("schedule_never_fires"));
            }
            return Result.ok("Scheduled '" + name + "' as " + routine.id().value()
                    + RoutineSchedule.nextFire(routine)
                    .map(next -> ". Next fire: " + next)
                    .orElse(""));
        }
    }

    /** Lists scheduled work. */
    public static final class ListTriggers implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "trigger_list",
                "List scheduled routines, their cron expressions, and when each next fires.",
                EffectClass.READ_LOCAL,
                Schemas.noArguments());

        private final RoutineStore store;

        public ListTriggers(RoutineStore store) {
            this.store = Objects.requireNonNull(store, "store");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            List<RoutineStore.Routine> routines = store.list(invocation.scope());
            if (routines.isEmpty()) {
                return Result.ok("(no scheduled routines)");
            }
            return Result.ok(routines.stream()
                    .map(routine -> "- " + routine.id().value() + " '" + routine.name() + "' "
                            + routine.trigger()
                            + (routine.enabled() ? "" : " (paused)")
                            + RoutineSchedule.nextFire(routine)
                            .map(next -> " next=" + next)
                            .orElse(" (never fires)"))
                    .collect(Collectors.joining("\n")));
        }
    }

    /**
     * Pauses or resumes a schedule.
     *
     * <p>One class serving both, because they differ only in the boolean they write — two
     * near-identical classes would be two places for the same logic to drift.
     */
    public static final class SetEnabled implements CapabilityHandler {

        private final RoutineStore store;
        private final boolean enable;
        private final CapabilityDescriptor descriptor;

        public SetEnabled(RoutineStore store, boolean enable) {
            this.store = Objects.requireNonNull(store, "store");
            this.enable = enable;
            this.descriptor = CapabilityDescriptor.builtin(
                    enable ? "trigger_resume" : "trigger_pause",
                    enable
                            ? "Resume a paused scheduled routine."
                            : "Pause a scheduled routine without deleting it.",
                    // Resuming restores unattended execution, so it is classed like creating one.
                    // Pausing only ever reduces what happens, so it is a local write.
                    enable ? EffectClass.PROCESS : EffectClass.WRITE_LOCAL,
                    Schemas.object(
                            Schemas.properties("id", Schemas.string("Routine id.")),
                            List.of("id")));
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            String id = invocation.stringArg("id", "");
            if (id.isBlank()) {
                return Result.err(HandlerError.failed("id_required"));
            }
            boolean existed = store.setEnabled(new RoutineStore.RoutineId(id), enable);
            return existed
                    ? Result.ok((enable ? "Resumed " : "Paused ") + id)
                    : Result.err(HandlerError.failed("routine_not_found"));
        }
    }

    /** Deletes a schedule. */
    public static final class Remove implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "trigger_remove",
                "Delete a scheduled routine.",
                EffectClass.WRITE_LOCAL,
                Schemas.object(
                        Schemas.properties("id", Schemas.string("Routine id.")),
                        List.of("id")));

        private final RoutineStore store;

        public Remove(RoutineStore store) {
            this.store = Objects.requireNonNull(store, "store");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            String id = invocation.stringArg("id", "");
            return store.delete(new RoutineStore.RoutineId(id))
                    ? Result.ok("Removed " + id)
                    : Result.err(HandlerError.failed("routine_not_found"));
        }
    }
}
