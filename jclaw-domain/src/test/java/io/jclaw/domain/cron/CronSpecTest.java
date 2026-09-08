package io.jclaw.domain.cron;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronSpecTest {

    private static ZonedDateTime at(String iso) {
        return ZonedDateTime.parse(iso).withZoneSameInstant(ZoneOffset.UTC);
    }

    private static ZonedDateTime next(String expression, String from) {
        Optional<ZonedDateTime> fire = CronSpec.parse(expression).nextFireAfter(at(from));
        assertTrue(fire.isPresent(), "expected a firing for: " + expression);
        return fire.get();
    }

    @Test
    @DisplayName("every minute fires at the next whole minute, strictly after now")
    void everyMinute() {
        assertEquals(at("2026-03-01T10:06:00Z"), next("* * * * *", "2026-03-01T10:05:30Z"));
        // Exactly on a boundary must advance, not return the same instant.
        assertEquals(at("2026-03-01T10:06:00Z"), next("* * * * *", "2026-03-01T10:05:00Z"));
    }

    @Test
    @DisplayName("step and range fields")
    void stepsAndRanges() {
        assertEquals(at("2026-03-01T10:15:00Z"), next("*/15 * * * *", "2026-03-01T10:05:00Z"));
        assertEquals(at("2026-03-01T11:00:00Z"), next("0 9-17 * * *", "2026-03-01T10:30:00Z"));
        assertEquals(at("2026-03-02T09:00:00Z"), next("0 9 * * *", "2026-03-01T10:00:00Z"));
    }

    @Test
    @DisplayName("day-of-month and day-of-week union follows Vixie cron")
    void domDowUnion() {
        // Both restricted: fires on the 1st OR any Monday, not only Mondays that are the 1st.
        CronSpec spec = CronSpec.parse("0 0 1 * MON");

        // 2026-06-01 is a Monday; check a month where they diverge. June 2026: 1st is Monday.
        // Use September 2026 — 1st is a Tuesday, Mondays are 7,14,21,28.
        assertTrue(spec.matches(at("2026-09-01T00:00:00Z")), "the 1st should fire even though it is a Tuesday");
        assertTrue(spec.matches(at("2026-09-07T00:00:00Z")), "a Monday should fire even though it is not the 1st");
        assertFalse(spec.matches(at("2026-09-08T00:00:00Z")), "a plain Tuesday should not fire");
    }

    @Test
    @DisplayName("only day-of-month restricted means day-of-week is ignored")
    void onlyDomRestricted() {
        CronSpec spec = CronSpec.parse("0 0 15 * *");

        assertTrue(spec.matches(at("2026-09-15T00:00:00Z")));
        assertFalse(spec.matches(at("2026-09-16T00:00:00Z")));
    }

    @Test
    @DisplayName("only day-of-week restricted means day-of-month is ignored")
    void onlyDowRestricted() {
        CronSpec spec = CronSpec.parse("0 0 * * SUN");

        assertTrue(spec.matches(at("2026-09-06T00:00:00Z")), "2026-09-06 is a Sunday");
        assertFalse(spec.matches(at("2026-09-07T00:00:00Z")));
    }

    @Test
    @DisplayName("day 7 and day 0 both mean Sunday")
    void sundayIsZeroOrSeven() {
        assertTrue(CronSpec.parse("0 0 * * 0").matches(at("2026-09-06T00:00:00Z")));
        assertTrue(CronSpec.parse("0 0 * * 7").matches(at("2026-09-06T00:00:00Z")));
    }

    @Test
    @DisplayName("month rollover and leap years")
    void monthRollover() {
        assertEquals(at("2027-01-01T00:00:00Z"), next("0 0 1 1 *", "2026-06-15T12:00:00Z"));
        // 2028 is the next leap year after 2026.
        assertEquals(at("2028-02-29T00:00:00Z"), next("0 0 29 2 *", "2026-03-01T00:00:00Z"));
    }

    @Test
    @DisplayName("an impossible expression returns empty rather than looping forever")
    void impossibleExpressionTerminates() {
        // February never has 30 days.
        assertTrue(CronSpec.parse("0 0 30 2 *").nextFireAfter(at("2026-01-01T00:00:00Z")).isEmpty());
    }

    @Test
    @DisplayName("malformed expressions are rejected with a useful message")
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("* * * *"), "too few fields");
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("60 * * * *"), "minute out of range");
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("* 24 * * *"), "hour out of range");
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("*/0 * * * *"), "zero step");
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("x * * * *"), "non-numeric");
    }

    @Test
    @DisplayName("lists and named days parse")
    void listsAndNames() {
        CronSpec spec = CronSpec.parse("0,30 * * * MON,FRI");

        assertTrue(spec.matches(at("2026-09-07T10:00:00Z")), "Monday at :00");
        assertTrue(spec.matches(at("2026-09-11T10:30:00Z")), "Friday at :30");
        assertFalse(spec.matches(at("2026-09-09T10:00:00Z")), "Wednesday");
    }

    @Test
    @DisplayName("computation is pure — the same inputs always give the same answer")
    void deterministic() {
        String expression = "*/7 3 * * *";
        ZonedDateTime from = at("2026-05-05T02:59:00Z");

        assertEquals(
                CronSpec.parse(expression).nextFireAfter(from),
                CronSpec.parse(expression).nextFireAfter(from));
    }
}
