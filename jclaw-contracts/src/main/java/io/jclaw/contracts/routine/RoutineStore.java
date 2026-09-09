package io.jclaw.contracts.routine;

import io.jclaw.contracts.turn.Ident;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Scheduled agent work: a prompt, a cron expression, and the record of when it last ran.
 *
 * <p>A routine is not a background thread. It is a durable intention — "run this prompt every
 * weekday at 09:00" — and something has to come along and act on it. Keeping the intention
 * separate from the mechanism that fires it is what lets the same routine be driven by a long-lived
 * worker, by system cron calling {@code jclaw routines run-due}, or by a test with a fixed clock.
 */
public interface RoutineStore {

    /** Identity of a routine. */
    record RoutineId(String value) implements Ident {
        public RoutineId {
            value = Ident.validate(value, "RoutineId");
        }

        public static RoutineId fresh() {
            return new RoutineId(Ident.fresh("rtn"));
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * One scheduled routine.
     *
     * @param trigger        what makes this routine fire: a five-field cron expression
     *                       (evaluated in {@code zone}), {@code every <interval>},
     *                       {@code webhook sha256:<hex>}, or {@code on <event> k=v}
     * @param zone           IANA time zone id; stored explicitly because "09:00" means different
     *                       instants in different zones, and a routine that silently shifts by an
     *                       hour twice a year is a bug nobody attributes to the scheduler
     * @param lastFiredAt    empty until the first firing; the anchor the next fire is computed from
     */
    record Routine(
            RoutineId id,
            TurnScope scope,
            String name,
            String trigger,
            String zone,
            String prompt,
            ThreadId thread,
            boolean enabled,
            Optional<Instant> lastFiredAt,
            Instant createdAt) {

        public Routine {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(lastFiredAt, "lastFiredAt");
            Objects.requireNonNull(createdAt, "createdAt");
            if (prompt.isBlank()) {
                throw new IllegalArgumentException("routine prompt must not be blank");
            }
        }

        /** The instant scheduling is measured from: the last firing, or creation if never fired. */
        public Instant anchor() {
            return lastFiredAt.orElse(createdAt);
        }

        public Routine withEnabled(boolean enabled) {
            return new Routine(id, scope, name, trigger, zone, prompt, thread, enabled,
                    lastFiredAt, createdAt);
        }

        public Routine withLastFiredAt(Instant firedAt) {
            return new Routine(id, scope, name, trigger, zone, prompt, thread, enabled,
                    Optional.of(firedAt), createdAt);
        }
    }

    /** Stores a routine and mints its id. */
    Routine create(
            TurnScope scope, String name, String trigger, String zone,
            String prompt, ThreadId thread);

    Optional<Routine> find(RoutineId id);

    /** Every routine in a scope, oldest first. */
    List<Routine> list(TurnScope scope);

    /** Enables or pauses a routine. Returns whether it existed. */
    boolean setEnabled(RoutineId id, boolean enabled);

    /** Records a firing, which moves the anchor the next fire is computed from. */
    void recordFiring(RoutineId id, Instant firedAt);

    /** Removes a routine. Returns whether it existed. */
    boolean delete(RoutineId id);
}
