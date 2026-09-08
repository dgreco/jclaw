package io.jclaw.domain.cron;

import io.jclaw.contracts.routine.RoutineStore.Routine;
import io.jclaw.contracts.routine.RoutineStore.RoutineId;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduling is pure, so the awkward cases — double-firing, missed windows, unschedulable
 * expressions — are tested by passing an instant rather than by waiting for one.
 */
class RoutineScheduleTest {

    private static final TurnScope SCOPE = TurnScope.local("proj", new ThreadId("t"));

    private static Routine routine(
            String cron, String zone, Instant createdAt, Optional<Instant> lastFired, boolean enabled) {
        return new Routine(
                new RoutineId("rtn_test"), SCOPE, "test", cron, zone, "do the thing",
                new ThreadId("test"), enabled, lastFired, createdAt);
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    @DisplayName("a routine is due once its next fire has passed")
    void dueAfterNextFire() {
        // Created at 09:00, fires hourly. At 09:30 the 10:00 slot has not arrived.
        Routine hourly = routine("0 * * * *", "UTC", at("2026-03-01T09:00:00Z"), Optional.empty(), true);

        assertTrue(RoutineSchedule.due(List.of(hourly), at("2026-03-01T09:30:00Z")).isEmpty());
        assertEquals(1, RoutineSchedule.due(List.of(hourly), at("2026-03-01T10:00:00Z")).size());
        assertEquals(1, RoutineSchedule.due(List.of(hourly), at("2026-03-01T11:45:00Z")).size());
    }

    @Test
    @DisplayName("recording a firing moves the anchor, so it does not fire twice for one slot")
    void firingMovesAnchor() {
        Routine hourly = routine("0 * * * *", "UTC", at("2026-03-01T09:00:00Z"), Optional.empty(), true);
        Instant now = at("2026-03-01T10:00:00Z");

        assertEquals(1, RoutineSchedule.due(List.of(hourly), now).size(), "due at 10:00");

        Routine fired = hourly.withLastFiredAt(now);
        assertTrue(RoutineSchedule.due(List.of(fired), now).isEmpty(),
                "the same instant must not fire it again");
        assertEquals(1, RoutineSchedule.due(List.of(fired), at("2026-03-01T11:00:00Z")).size(),
                "but the next slot should");
    }

    @Test
    @DisplayName("a long outage fires once on waking, not once per missed slot")
    void catchesUpOnceNotPerSlot() {
        // Last fired at 09:00; the worker wakes six hours later. Six hourly slots were missed.
        Routine hourly = routine("0 * * * *", "UTC",
                at("2026-03-01T00:00:00Z"), Optional.of(at("2026-03-01T09:00:00Z")), true);

        List<RoutineSchedule.Due> due = RoutineSchedule.due(List.of(hourly), at("2026-03-01T15:00:00Z"));

        assertEquals(1, due.size(),
                "a missed window must produce one catch-up run, not a burst of six");
        assertEquals(at("2026-03-01T10:00:00Z"), due.get(0).scheduledFor().toInstant(),
                "and it should report the slot it is catching up for");
    }

    @Test
    @DisplayName("paused routines never fire")
    void pausedNeverFires() {
        Routine paused = routine("* * * * *", "UTC", at("2026-03-01T09:00:00Z"), Optional.empty(), false);

        assertTrue(RoutineSchedule.due(List.of(paused), at("2026-03-01T23:00:00Z")).isEmpty());
    }

    @Test
    @DisplayName("an unschedulable expression is skipped, not thrown, and does not block others")
    void unschedulableIsSkipped() {
        Routine impossible = routine("0 0 30 2 *", "UTC", at("2026-01-01T00:00:00Z"), Optional.empty(), true);
        Routine fine = routine("0 * * * *", "UTC", at("2026-01-01T00:00:00Z"), Optional.empty(), true);

        assertFalse(RoutineSchedule.isSchedulable(impossible));
        assertTrue(RoutineSchedule.nextFire(impossible).isEmpty());

        // The bad one must not prevent the good one from being reported.
        List<RoutineSchedule.Due> due =
                RoutineSchedule.due(List.of(impossible, fine), at("2026-01-01T05:00:00Z"));
        assertEquals(1, due.size());
        assertEquals("rtn_test", due.get(0).routine().id().value());
    }

    @Test
    @DisplayName("a malformed cron or unknown zone is skipped rather than crashing the scheduler")
    void malformedIsSkipped() {
        Routine badCron = routine("nonsense", "UTC", at("2026-01-01T00:00:00Z"), Optional.empty(), true);
        Routine badZone = routine("0 * * * *", "Mars/Olympus", at("2026-01-01T00:00:00Z"),
                Optional.empty(), true);

        assertTrue(RoutineSchedule.nextFire(badCron).isEmpty());
        assertTrue(RoutineSchedule.nextFire(badZone).isEmpty());
        assertTrue(RoutineSchedule.due(List.of(badCron, badZone), at("2026-06-01T00:00:00Z")).isEmpty());
    }

    @Test
    @DisplayName("the zone is honoured, so 09:00 means 09:00 locally")
    void zoneIsHonoured() {
        // 09:00 in Tokyo is 00:00 UTC.
        Routine tokyo = routine("0 9 * * *", "Asia/Tokyo",
                at("2026-03-01T00:00:00Z"), Optional.of(at("2026-03-01T00:00:00Z")), true);

        assertTrue(RoutineSchedule.due(List.of(tokyo), at("2026-03-01T20:00:00Z")).isEmpty(),
                "not yet due at 05:00 Tokyo the next day");
        assertEquals(1, RoutineSchedule.due(List.of(tokyo), at("2026-03-02T00:00:00Z")).size(),
                "due at 09:00 Tokyo, which is midnight UTC");
    }

    @Test
    @DisplayName("scheduling is deterministic")
    void deterministic() {
        Routine hourly = routine("0 * * * *", "UTC", at("2026-03-01T09:00:00Z"), Optional.empty(), true);
        Instant now = at("2026-03-01T12:00:00Z");

        assertEquals(
                RoutineSchedule.due(List.of(hourly), now).size(),
                RoutineSchedule.due(List.of(hourly), now).size());
        assertEquals(RoutineSchedule.nextFire(hourly), RoutineSchedule.nextFire(hourly));
    }
}
