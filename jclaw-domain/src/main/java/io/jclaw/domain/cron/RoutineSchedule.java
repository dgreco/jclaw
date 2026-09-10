// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.cron;

import io.jclaw.contracts.routine.RoutineStore.Routine;
import io.jclaw.domain.trigger.Trigger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure scheduling decisions over routines.
 *
 * <p>Answers one question — which routines are due — as a function of the routines, the clock, and
 * nothing else. Keeping this pure means the awkward cases are testable by passing an instant rather
 * than by waiting: a routine that should not double-fire, one whose zone crosses a DST boundary,
 * one whose cron can never match.
 *
 * <p>The anchor rule matters. Due-ness is computed from the <em>last firing</em>, not from the
 * present moment, so a worker that was asleep for six hours fires each routine once on waking
 * rather than either skipping it or firing it once per missed slot. Catch-up storms and silent
 * skips are both worse than one late run.
 */
public final class RoutineSchedule {

    private RoutineSchedule() {
    }

    /** A routine paired with the moment it became due. */
    public record Due(Routine routine, ZonedDateTime scheduledFor) {
        public Due {
            Objects.requireNonNull(routine, "routine");
            Objects.requireNonNull(scheduledFor, "scheduledFor");
        }
    }

    /**
     * Routines that should fire at or before {@code now}.
     *
     * <p>Disabled routines are excluded, as are those whose cron expression is malformed or can
     * never match — a routine that cannot be scheduled is reported by {@link #nextFire} returning
     * empty rather than by throwing here, so one bad expression cannot stop every other routine
     * from running.
     */
    public static List<Due> due(List<Routine> routines, Instant now) {
        Objects.requireNonNull(routines, "routines");
        Objects.requireNonNull(now, "now");

        return routines.stream()
                .filter(Routine::enabled)
                .map(routine -> nextFire(routine).map(fire -> new Due(routine, fire)))
                .flatMap(Optional::stream)
                .filter(candidate -> !candidate.scheduledFor().toInstant().isAfter(now))
                .toList();
    }

    /**
     * The next instant a routine should fire, computed from its anchor.
     *
     * @return empty when the expression is malformed, the zone is unknown, or the schedule can
     *         never match (for example {@code 0 0 30 2 *})
     */
    public static Optional<ZonedDateTime> nextFire(Routine routine) {
        Objects.requireNonNull(routine, "routine");
        try {
            ZoneId zone = ZoneId.of(routine.zone());
            Trigger trigger = Trigger.parse(routine.trigger()).toOptional().orElse(null);
            return switch (trigger) {
                case Trigger.Cron cron -> CronSpec.parse(cron.expression())
                        .nextFireAfter(ZonedDateTime.ofInstant(routine.anchor(), zone));
                case Trigger.Heartbeat heartbeat ->
                        Optional.of(ZonedDateTime.ofInstant(routine.anchor().plus(heartbeat.interval()), zone));
                // A webhook, an event, or a file change decides when these fire; not the clock.
                case Trigger.Webhook ignored -> Optional.empty();
                case Trigger.OnEvent ignored -> Optional.empty();
                case Trigger.Watch ignored -> Optional.empty();
                case null -> Optional.empty();
            };
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether the routine can fire at all: a time-driven trigger with a next fire, or a
     * request- or event-driven trigger, which fires when its cause arrives.
     */
    public static boolean canFire(Routine routine) {
        Objects.requireNonNull(routine, "routine");
        return Trigger.parse(routine.trigger()).toOptional()
                .map(trigger -> !trigger.timeDriven() || nextFire(routine).isPresent())
                .orElse(false);
    }

    /** Whether a routine's schedule is valid and can fire at least once. Used to reject input early. */
    public static boolean isSchedulable(Routine routine) {
        return nextFire(routine).isPresent();
    }
}
