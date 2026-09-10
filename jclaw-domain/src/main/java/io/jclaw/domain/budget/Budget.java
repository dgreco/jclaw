// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.budget;

import io.jclaw.contracts.model.ModelExchange.Usage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable spend limits for one run, and the accounting against them.
 *
 * <p>Pure by construction: {@link #charge} returns a new {@code Budget} rather than mutating, so
 * a speculative "would this fit?" check is just an ordinary call and never leaves residue. The
 * clock is passed in rather than read, which is what makes the whole turn machine reproducible —
 * replaying a run with the same inputs produces the same decisions, including the moment it
 * decided it had run out of time.
 *
 * @param maxTokens     total input+output tokens allowed, or {@code 0} for unlimited
 * @param maxIterations tick-cycle cap; the backstop against a model that loops forever
 * @param wallClock     maximum elapsed time, or {@link Duration#ZERO} for unlimited
 * @param startedAt     run start, supplied by the caller
 * @param spent         accumulated usage so far
 * @param iterations    completed iterations so far
 */
public record Budget(
        long maxTokens,
        int maxIterations,
        Duration wallClock,
        Instant startedAt,
        Usage spent,
        int iterations) {

    public Budget {
        Objects.requireNonNull(wallClock, "wallClock");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(spent, "spent");
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be non-negative");
        }
        if (maxIterations < 0) {
            throw new IllegalArgumentException("maxIterations must be non-negative");
        }
        if (wallClock.isNegative()) {
            throw new IllegalArgumentException("wallClock must be non-negative");
        }
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be non-negative");
        }
    }

    /** A fresh budget with the given limits. */
    public static Budget of(long maxTokens, int maxIterations, Duration wallClock, Instant startedAt) {
        return new Budget(maxTokens, maxIterations, wallClock, startedAt, Usage.ZERO, 0);
    }

    /** Sensible defaults for an interactive CLI run. */
    public static Budget interactive(Instant startedAt) {
        return of(500_000, 50, Duration.ofMinutes(10), startedAt);
    }

    /** Records model spend, returning the updated budget. */
    public Budget charge(Usage usage) {
        return new Budget(maxTokens, maxIterations, wallClock, startedAt, spent.plus(usage), iterations);
    }

    /** Advances the iteration counter. */
    public Budget nextIteration() {
        return new Budget(maxTokens, maxIterations, wallClock, startedAt, spent, iterations + 1);
    }

    /**
     * Why the budget is exhausted, or {@link Exhaustion#NONE} when there is room left.
     *
     * @param now current time, supplied by the caller so this stays a pure function
     */
    public Exhaustion exhaustion(Instant now) {
        Objects.requireNonNull(now, "now");
        if (maxTokens > 0 && spent.total() >= maxTokens) {
            return Exhaustion.TOKENS;
        }
        if (maxIterations > 0 && iterations >= maxIterations) {
            return Exhaustion.ITERATIONS;
        }
        if (!wallClock.isZero() && Duration.between(startedAt, now).compareTo(wallClock) >= 0) {
            return Exhaustion.WALL_CLOCK;
        }
        return Exhaustion.NONE;
    }

    /** Convenience predicate over {@link #exhaustion}. */
    public boolean isExhausted(Instant now) {
        return exhaustion(now) != Exhaustion.NONE;
    }

    /** Tokens still available, or {@link Long#MAX_VALUE} when uncapped. */
    public long remainingTokens() {
        return maxTokens == 0 ? Long.MAX_VALUE : Math.max(0, maxTokens - spent.total());
    }

    /** Which limit was hit. */
    public enum Exhaustion {
        NONE,
        TOKENS,
        ITERATIONS,
        WALL_CLOCK;

        /** Short stable token for audit lines. */
        public String reason() {
            return switch (this) {
                case NONE -> "none";
                case TOKENS -> "token_budget";
                case ITERATIONS -> "iteration_budget";
                case WALL_CLOCK -> "wall_clock_budget";
            };
        }
    }
}
