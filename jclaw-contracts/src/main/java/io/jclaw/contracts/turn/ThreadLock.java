// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.turn;

import java.util.Optional;

/**
 * Mutual exclusion over a canonical thread: at most one run executes on a thread at a time.
 *
 * <p>Without it, two {@code jclaw run -t x} processes started together both accept their inbound
 * message, both seed from the same history, and both append replies: the transcript interleaves
 * two conversations that each believed it was the only one. IronClaw enforces "one active run per
 * canonical thread" in its turn coordinator before any side effect; this port is the same rule.
 *
 * <p>Acquired <em>before</em> the inbound message is made durable, so a refused submission leaves
 * no trace in the transcript or the run store. The caller is told to try again, and nothing has
 * to be undone. Released when the run reaches a terminal or parked state. A parked run does not
 * hold the lock: it is not executing, and holding a thread hostage until a human approves a gate
 * would make an unattended approval flow deadlock the thread.
 *
 * <p>Ownership is a handle, not a boolean. Releasing is {@link Held#close()}, so a
 * try-with-resources or a {@code finally} makes it structurally impossible to return from a turn
 * without releasing, which is the failure mode a "boolean unlock(scope)" API invites.
 */
public interface ThreadLock {

    /**
     * Attempts to take the lock for a scope's thread.
     *
     * @return the held lock, or empty when another run, in this process or any other on the same
     *         host, currently holds it. Never blocks: the caller decides what to tell the user.
     */
    Optional<Held> tryAcquire(TurnScope scope);

    /** A held lock. Closing releases it; closing twice is harmless. */
    interface Held extends AutoCloseable {

        TurnScope scope();

        @Override
        void close();
    }
}
