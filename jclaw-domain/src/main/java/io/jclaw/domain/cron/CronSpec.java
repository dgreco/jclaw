// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.cron;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A parsed five-field cron expression and the pure arithmetic for its next firing.
 *
 * <p>Fields, in order: {@code minute hour day-of-month month day-of-week}. Supports {@code *},
 * literals, ranges ({@code 1-5}), lists ({@code 1,3,5}), and steps ({@code (asterisk)/15},
 * {@code 1-9/2}). Day-of-week accepts {@code 0} or {@code 7} for Sunday.
 *
 * <p>The day-of-month / day-of-week interaction follows Vixie cron, which is surprising but
 * standard: when <em>both</em> are restricted the match is their <b>union</b>, not intersection.
 * So {@code 0 0 1 * MON} fires on the first of the month <em>and</em> every Monday. When only one
 * is restricted, only that one applies. Getting this wrong is the classic cron bug, so it is
 * tested explicitly.
 *
 * <p>Pure: {@link #nextFireAfter} takes the reference time as an argument and reads no clock, so
 * schedule computation is reproducible and testable without waiting.
 */
public record CronSpec(BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months, BitSet daysOfWeek,
                       boolean domRestricted, boolean dowRestricted, String expression) {

    /** Guard against pathological expressions that never match, e.g. Feb 30. */
    private static final int MAX_SEARCH_DAYS = 366 * 4;

    public CronSpec {
        Objects.requireNonNull(minutes, "minutes");
        Objects.requireNonNull(hours, "hours");
        Objects.requireNonNull(daysOfMonth, "daysOfMonth");
        Objects.requireNonNull(months, "months");
        Objects.requireNonNull(daysOfWeek, "daysOfWeek");
        Objects.requireNonNull(expression, "expression");
    }

    /**
     * Parses a five-field expression.
     *
     * @throws IllegalArgumentException if the expression is malformed or out of range
     */
    public static CronSpec parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException(
                    "cron expression needs exactly 5 fields, got " + fields.length + ": " + expression);
        }
        return new CronSpec(
                parseField(fields[0], 0, 59, "minute"),
                parseField(fields[1], 0, 23, "hour"),
                parseField(fields[2], 1, 31, "day-of-month"),
                parseField(fields[3], 1, 12, "month"),
                parseDayOfWeek(fields[4]),
                !"*".equals(fields[2].trim()),
                !"*".equals(fields[4].trim()),
                expression.trim());
    }

    /**
     * The next instant strictly after {@code from} that matches this expression.
     *
     * <p>Returns empty when no match exists within {@link #MAX_SEARCH_DAYS} — an expression like
     * {@code 0 0 30 2 *} (February 30th) can never fire, and returning empty is more honest than
     * looping forever.
     */
    public Optional<ZonedDateTime> nextFireAfter(ZonedDateTime from) {
        Objects.requireNonNull(from, "from");

        // Start at the next whole minute; cron has minute resolution and must fire strictly after.
        ZonedDateTime candidate = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        ZonedDateTime limit = candidate.plusDays(MAX_SEARCH_DAYS);

        while (candidate.isBefore(limit)) {
            if (!months.get(candidate.getMonthValue())) {
                // Skip to the first minute of the next month.
                candidate = candidate.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
                continue;
            }
            if (!dayMatches(candidate)) {
                candidate = candidate.plusDays(1).truncatedTo(ChronoUnit.DAYS);
                continue;
            }
            if (!hours.get(candidate.getHour())) {
                candidate = candidate.plusHours(1).truncatedTo(ChronoUnit.HOURS);
                continue;
            }
            if (!minutes.get(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Whether {@code time} matches this expression to the minute. */
    public boolean matches(ZonedDateTime time) {
        Objects.requireNonNull(time, "time");
        return months.get(time.getMonthValue())
                && dayMatches(time)
                && hours.get(time.getHour())
                && minutes.get(time.getMinute());
    }

    /**
     * Vixie-cron day matching: union when both day fields are restricted, otherwise whichever is
     * restricted, and any day when neither is.
     */
    private boolean dayMatches(ZonedDateTime time) {
        boolean domHit = daysOfMonth.get(time.getDayOfMonth());
        boolean dowHit = daysOfWeek.get(toCronDow(time.getDayOfWeek()));

        if (domRestricted && dowRestricted) {
            return domHit || dowHit;
        }
        if (domRestricted) {
            return domHit;
        }
        if (dowRestricted) {
            return dowHit;
        }
        return true;
    }

    /** java.time uses MONDAY=1..SUNDAY=7; cron uses SUNDAY=0..SATURDAY=6. */
    private static int toCronDow(DayOfWeek day) {
        return day == DayOfWeek.SUNDAY ? 0 : day.getValue();
    }

    private static BitSet parseDayOfWeek(String field) {
        BitSet parsed = parseField(normalizeDayNames(field), 0, 7, "day-of-week");
        // Accept 7 as Sunday by folding it onto 0.
        if (parsed.get(7)) {
            parsed.set(0);
            parsed.clear(7);
        }
        return parsed;
    }

    private static String normalizeDayNames(String field) {
        String upper = field.toUpperCase(java.util.Locale.ROOT);
        List<String> names = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
        for (int i = 0; i < names.size(); i++) {
            upper = upper.replace(names.get(i), Integer.toString(i));
        }
        return upper;
    }

    private static BitSet parseField(String field, int min, int max, String name) {
        BitSet bits = new BitSet(max + 1);
        for (String part : field.trim().split(",")) {
            parsePart(part.trim(), min, max, name, bits);
        }
        if (bits.isEmpty()) {
            throw new IllegalArgumentException(name + " field matches nothing: " + field);
        }
        return bits;
    }

    private static void parsePart(String part, int min, int max, String name, BitSet bits) {
        int step = 1;
        String range = part;

        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            step = parseInt(part.substring(slash + 1), name + " step");
            if (step <= 0) {
                throw new IllegalArgumentException(name + " step must be positive: " + part);
            }
        }

        int from;
        int to;
        if ("*".equals(range)) {
            from = min;
            to = max;
        } else {
            int dash = range.indexOf('-');
            if (dash >= 0) {
                from = parseInt(range.substring(0, dash), name);
                to = parseInt(range.substring(dash + 1), name);
            } else {
                from = parseInt(range, name);
                // A bare literal with a step means "from here to the end", as Vixie cron does.
                to = slash >= 0 ? max : from;
            }
        }

        if (from < min || to > max || from > to) {
            throw new IllegalArgumentException(
                    name + " out of range [" + min + "," + max + "]: " + part);
        }
        for (int value = from; value <= to; value += step) {
            bits.set(value);
        }
    }

    private static int parseInt(String text, String name) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + name + " value: " + text, e);
        }
    }

    @Override
    public String toString() {
        return expression;
    }
}
