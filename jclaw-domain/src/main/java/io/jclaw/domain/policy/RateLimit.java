package io.jclaw.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sliding-window rate limit: at most {@code permits} invocations in any {@code window}.
 *
 * <p>Pure. The caller keeps the history of invocation instants and passes the clock; this class
 * only answers whether one more is allowed now. Sliding rather than fixed windows, so a burst at
 * a boundary cannot double the effective rate.
 */
public record RateLimit(int permits, Duration window) {

    private static final Pattern SPEC = Pattern.compile("^\\s*(\\d+)\\s*/\\s*(\\d+)\\s*([smh])\\s*$");

    public RateLimit {
        Objects.requireNonNull(window, "window");
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive, got " + permits);
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, got " + window);
        }
    }

    /**
     * Parses {@code N/Ws}, {@code N/Wm}, or {@code N/Wh}: {@code 5/1m} is five per minute.
     */
    public static RateLimit parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        Matcher matcher = SPEC.matcher(spec);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "rate limit '" + spec + "' is not N/<duration> (e.g. 5/1m, 100/1h, 2/30s)");
        }
        int permits = Integer.parseInt(matcher.group(1));
        long amount = Long.parseLong(matcher.group(2));
        Duration window = switch (matcher.group(3)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
        return new RateLimit(permits, window);
    }

    /** Whether one more invocation at {@code now} stays within the limit, given the history. */
    public boolean permits(List<Instant> priorInvocations, Instant now) {
        Objects.requireNonNull(priorInvocations, "priorInvocations");
        Objects.requireNonNull(now, "now");
        Instant since = now.minus(window);
        long recent = priorInvocations.stream().filter(at -> at.isAfter(since)).count();
        return recent < permits;
    }

    /** Human-readable form for denials and diagnostics, e.g. {@code 5 per 1m}. */
    public String describe() {
        return permits + " per " + window.toString().substring(2).toLowerCase(java.util.Locale.ROOT);
    }
}
