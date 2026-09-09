package io.jclaw.contracts.channel;

import java.util.Objects;
import java.util.Optional;

/**
 * Where a reply goes back to.
 *
 * <p>The CLI has no need of this: a reply is whatever the command printed. A channel does. A
 * message arrived from somewhere, possibly in a thread, and the answer has to return to that
 * exact place, possibly minutes later on a different machine, after the run was queued, parked on
 * a gate, and resumed. So the destination is a durable value bound to the conversation rather
 * than a callback held in memory.
 *
 * @param adapter      which channel this belongs to, matching {@link ChannelAdapter#id()}
 * @param conversation the channel's own id for the conversation, opaque here
 * @param thread       the channel's id for a thread within it, when the channel has threads
 */
public record ReplyTarget(String adapter, String conversation, Optional<String> thread) {

    public ReplyTarget {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(thread, "thread");
        if (adapter.isBlank() || conversation.isBlank()) {
            throw new IllegalArgumentException("a reply target needs an adapter and a conversation");
        }
    }

    public ReplyTarget(String adapter, String conversation) {
        this(adapter, conversation, Optional.empty());
    }

    /**
     * The jclaw thread this conversation maps to.
     *
     * <p>Derived rather than random so the same channel conversation always resumes the same
     * jclaw thread, which is what makes a channel feel like a continuing conversation instead of
     * a series of unrelated questions.
     */
    public String threadName() {
        return adapter + ":" + conversation + thread.map(t -> "/" + t).orElse("");
    }
}
