// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetentionTest {

    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    @DisplayName("old rows of finished runs are expendable")
    void oldAndFinished() {
        assertTrue(Retention.expendable(NOW.minus(Duration.ofDays(8)), true, NOW, WEEK));
        assertTrue(Retention.expendable(NOW.minus(WEEK), true, NOW, WEEK), "exactly at the boundary counts as old");
    }

    @Test
    @DisplayName("recent rows are kept")
    void recentKept() {
        assertFalse(Retention.expendable(NOW.minus(Duration.ofDays(6)), true, NOW, WEEK));
    }

    @Test
    @DisplayName("rows of unfinished or unknown runs are kept however old")
    void unfinishedKept() {
        assertFalse(Retention.expendable(NOW.minus(Duration.ofDays(400)), false, NOW, WEEK));
    }

    @Test
    @DisplayName("a zero age keeps forever")
    void zeroKeepsForever() {
        assertTrue(Retention.keepsForever(Duration.ZERO));
        assertTrue(Retention.keepsForever(Duration.ofDays(-1)));
        assertFalse(Retention.expendable(NOW.minus(Duration.ofDays(400)), true, NOW, Duration.ZERO));
    }
}
