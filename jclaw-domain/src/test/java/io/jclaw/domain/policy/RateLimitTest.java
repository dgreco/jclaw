// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("parses N/duration in seconds, minutes, and hours")
    void parses() {
        assertEquals(new RateLimit(5, Duration.ofMinutes(1)), RateLimit.parse("5/1m"));
        assertEquals(new RateLimit(100, Duration.ofHours(1)), RateLimit.parse(" 100 / 1h "));
        assertEquals(new RateLimit(2, Duration.ofSeconds(30)), RateLimit.parse("2/30s"));
        assertThrows(IllegalArgumentException.class, () -> RateLimit.parse("5"));
        assertThrows(IllegalArgumentException.class, () -> RateLimit.parse("0/1m"));
        assertThrows(IllegalArgumentException.class, () -> RateLimit.parse("5/1d"));
    }

    @Test
    @DisplayName("the window slides: old invocations stop counting")
    void slidingWindow() {
        RateLimit limit = RateLimit.parse("2/1m");
        List<Instant> history = List.of(T0, T0.plusSeconds(10));

        assertFalse(limit.permits(history, T0.plusSeconds(20)), "two in the last minute: full");
        assertTrue(limit.permits(history, T0.plusSeconds(61)), "the first has aged out");
        assertTrue(limit.permits(List.of(), T0));
    }

    @Test
    @DisplayName("describes itself for denials")
    void describes() {
        assertEquals("5 per 1m", RateLimit.parse("5/1m").describe());
        assertEquals("2 per 30s", RateLimit.parse("2/30s").describe());
    }
}
