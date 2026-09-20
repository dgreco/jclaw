// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.turn;

import java.util.Set;

/**
 * Durable lifecycle state of a run.
 *
 * <p>Mirrors IronClaw's runner state machine:
 * <pre>
 *   Queued -> Running -> {Completed, Failed, Cancelled}
 *   Running -> {BlockedApproval, BlockedAuth, WaitingProcess} -> Queued (on resume)
 * </pre>
 *
 * <p>Blocked states keep the active-thread lock: the run has not finished, it is parked awaiting
 * a human or an external process, and letting a sibling run start would violate the one-active-run
 * invariant. Only terminal states release the lock.
 */
public enum TurnStatus {

    /** Admitted and durable, but no side effect has run. Safe to claim. */
    QUEUED,

    /** Claimed by a runner holding a lease. Model and tool effects may occur. */
    RUNNING,

    /** Parked on a human approval gate. Holds the active lock. */
    BLOCKED_APPROVAL,

    /** Parked on an authentication/authorization gate. Holds the active lock. */
    BLOCKED_AUTH,

    /** Parked awaiting an external process or child run. Holds the active lock. */
    WAITING_PROCESS,

    /** Terminal success, validated against durable reply evidence. */
    COMPLETED,

    /** Terminal failure carrying a stable redacted category. */
    FAILED,

    /** Terminal cancellation, validated against cancellation evidence. */
    CANCELLED;

    private static final Set<TurnStatus> TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED);
    private static final Set<TurnStatus> BLOCKED = Set.of(BLOCKED_APPROVAL, BLOCKED_AUTH, WAITING_PROCESS);

    /** Terminal states release the active-thread lock and admit no further transition. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Blocked states retain the active-thread lock and may be resumed. */
    public boolean isBlocked() {
        return BLOCKED.contains(this);
    }

    /** True while this run owns the active-thread lock. */
    public boolean holdsActiveLock() {
        return !isTerminal();
    }

    /** Only a blocked run may be resumed; queued work is claimed, not resumed. */
    public boolean isResumable() {
        return isBlocked();
    }

    /**
     * Whether a transition to {@code next} is legal. The runner consults this before writing
     * durable state so an out-of-order or replayed transition fails loudly rather than
     * corrupting the lifecycle.
     */
    public boolean canTransitionTo(TurnStatus next) {
        if (this == next) {
            return this == RUNNING; // heartbeat re-assert is the only self-transition
        }
        if (isTerminal()) {
            return false; // terminal is forever
        }
        return switch (this) {
            case QUEUED -> next == RUNNING || next == CANCELLED || next == FAILED;
            case RUNNING -> true; // may block, complete, fail, or cancel
            case BLOCKED_APPROVAL, BLOCKED_AUTH, WAITING_PROCESS ->
                    next == QUEUED || next == CANCELLED || next == FAILED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
