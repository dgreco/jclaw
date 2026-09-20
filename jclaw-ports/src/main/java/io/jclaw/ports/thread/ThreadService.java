// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.thread;

import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.turn.MessageId;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnRef.AcceptedMessageRef;
import io.jclaw.ports.turn.TurnRef.LoopMessageRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Source of truth for user-visible conversation: accepted inbound messages and finalized
 * assistant replies.
 *
 * <p>Deliberately separate from the event log and from run state. The transcript answers "what
 * did the human and the assistant say", which is a different question from "what did the runtime
 * do" — conflating them is how progress metadata ends up rendered as conversation.
 *
 * <p>This service is also the sole minter of message refs. A driver cannot fabricate a
 * {@link LoopMessageRef} that will survive exit validation, because validation resolves it here.
 */
public interface ThreadService {

    /** A durable transcript entry. */
    record ThreadMessage(
            MessageId id,
            ThreadId thread,
            ChatMessage message,
            Instant createdAt,
            boolean draft) {

        public ThreadMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public boolean isFinal() {
            return !draft;
        }
    }

    /** Creates a thread if absent, returning it either way. */
    ThreadId ensureThread(ThreadId thread);

    /**
     * Records an inbound user message and mints the ref the turn coordinator will admit.
     * Called before {@code submitTurn} so the message is durable before any run exists.
     */
    AcceptedMessageRef acceptInbound(ThreadId thread, ChatMessage message);

    /**
     * Records an assistant message and mints its ref.
     *
     * @param draft when true the message is a partial in-progress reply; drafts are replaced by
     *              the final message rather than accumulating in the transcript
     */
    LoopMessageRef appendAssistant(ThreadId thread, ChatMessage message, boolean draft);

    /** Resolves a ref to its message, or empty when it does not exist. The evidence check. */
    Optional<ThreadMessage> resolve(LoopMessageRef ref);

    /** Resolves an accepted inbound ref. */
    Optional<ThreadMessage> resolveAccepted(AcceptedMessageRef ref);

    /**
     * Conversation history, oldest first, excluding drafts.
     *
     * @param limit maximum messages to return, most recent kept when the thread is longer
     */
    List<ThreadMessage> history(ThreadId thread, int limit);

    /** Every thread known to the store, most recently active first. */
    List<ThreadId> listThreads(int limit);
}
