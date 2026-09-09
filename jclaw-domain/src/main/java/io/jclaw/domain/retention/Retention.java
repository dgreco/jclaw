package io.jclaw.domain.retention;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure retention rule for append-only stores.
 *
 * <p>A row may be dropped only when both hold: it is older than the store's maximum age, and the
 * run it belongs to is finished. The second condition is what keeps retention from ever being a
 * correctness risk: a parked run's checkpoint, a running run's results, or a queued run's events
 * are evidence something still depends on, however old they are. An unknown run is treated as
 * unfinished, because "we could not tell" must fail on the side of keeping.
 *
 * <p>A zero or negative maximum age means keep forever, which is the honest default for the
 * transcript and a sensible one for anything an operator has not decided about.
 */
public final class Retention {

    private Retention() {
    }

    /** Whether {@code maxAge} means "never drop". */
    public static boolean keepsForever(Duration maxAge) {
        Objects.requireNonNull(maxAge, "maxAge");
        return maxAge.isZero() || maxAge.isNegative();
    }

    /**
     * Whether a row may be dropped.
     *
     * @param storedAt    when the row was written
     * @param runFinished whether the row's run is terminal; false when unknown
     * @param now         the current instant, supplied so the rule stays pure
     * @param maxAge      the store's maximum age; zero keeps forever
     */
    public static boolean expendable(Instant storedAt, boolean runFinished, Instant now, Duration maxAge) {
        Objects.requireNonNull(storedAt, "storedAt");
        Objects.requireNonNull(now, "now");
        if (keepsForever(maxAge) || !runFinished) {
            return false;
        }
        return !storedAt.isAfter(now.minus(maxAge));
    }
}
