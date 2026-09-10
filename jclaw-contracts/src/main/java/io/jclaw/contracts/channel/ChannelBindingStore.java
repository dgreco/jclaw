// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.channel;

import io.jclaw.contracts.turn.ThreadId;

import java.util.List;
import java.util.Optional;

/**
 * Which conversation each thread came from, so a reply can find its way back.
 *
 * <p>Durable because the answer usually is not immediate. A turn from a channel may queue behind
 * others, park on an approval gate for an hour, and be resumed by a different process; the
 * destination has to outlive all of that. Keyed by thread, because a thread is what a channel
 * conversation maps to.
 */
public interface ChannelBindingStore {

    /** Records where replies on this thread go. Replaces any earlier binding. */
    void bind(ThreadId thread, ReplyTarget target);

    Optional<ReplyTarget> find(ThreadId thread);

    /** Every binding, for {@code doctor} and diagnostics. */
    List<ThreadId> boundThreads();

    /** Forgets a binding. Returns whether one existed. */
    boolean unbind(ThreadId thread);
}
