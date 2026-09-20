// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.routine.RoutineStore;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.domain.cron.RoutineSchedule;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires routines that have come due.
 *
 * <p>Deliberately not a daemon and not annotated {@code @Scheduled}. It exposes a single
 * "fire everything due right now" operation, which both {@code jclaw routines run-due} (suitable
 * for system cron) and {@code jclaw worker} (a polling loop) call. Keeping the firing decision
 * separate from whatever drives the clock means the same code path is used by a cron entry, a
 * long-lived worker, and a test with a fixed instant.
 *
 * <p>The firing record is written <em>before</em> the turn runs. If a routine's turn crashes the
 * process, the next pass must not fire it again immediately — a scheduled job that retries in a
 * tight loop is far more damaging than one that misses a slot.
 */
@Service
public class RoutineRunner {

    private final RoutineStore routines;
    private final JclawRuntime runtime;
    private final Clock clock;

    public RoutineRunner(RoutineStore routines, JclawRuntime runtime, Clock clock) {
        this.routines = Objects.requireNonNull(routines, "routines");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What happened to one fired routine. */
    public record Fired(RoutineStore.Routine routine, JclawRuntime.TurnResult result) {
    }

    /**
     * Fires every routine due at the current instant.
     *
     * @param cancelled polled between routines so an interrupt stops cleanly between turns rather
     *                  than mid-turn
     * @return one entry per routine fired, in the order they ran
     */
    public List<Fired> runDue(AtomicBoolean cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");

        Instant now = clock.instant();
        List<RoutineSchedule.Due> due = RoutineSchedule.due(
                routines.list(runtime.scopeFor(new ThreadId("routines"))), now);

        List<Fired> fired = new ArrayList<>();
        for (RoutineSchedule.Due candidate : due) {
            if (cancelled.get()) {
                break;
            }
            RoutineStore.Routine routine = candidate.routine();

            // Record first: a crash mid-turn must not cause an immediate re-fire.
            routines.recordFiring(routine.id(), now);

            JclawRuntime.TurnResult result =
                    runtime.submit(routine.thread(), routine.prompt(), cancelled);
            fired.add(new Fired(routine, result));
        }
        return fired;
    }

    /** Routines currently due, without firing them. Backs {@code routines list --due}. */
    public List<RoutineSchedule.Due> peekDue() {
        return RoutineSchedule.due(
                routines.list(runtime.scopeFor(new ThreadId("routines"))),
                clock.instant());
    }
}
