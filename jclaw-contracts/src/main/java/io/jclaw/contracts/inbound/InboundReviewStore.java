package io.jclaw.contracts.inbound;

import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Foreign messages held back for a person to look at.
 *
 * <p>Only reached under {@code inbound-policy: review}, and only by content the screening judged
 * {@code HIGH}. The queue exists so that policy has somewhere to put a message: without it
 * "review" could only mean "drop", which is what {@code block} already means.
 *
 * <p>A held message has not started a turn. Nothing was enqueued, no run exists, no thread was
 * locked — approving is what creates the turn, and discarding leaves no trace beyond the record
 * that it arrived and was refused. That ordering is deliberate: a message a human has not looked
 * at should not have consumed a thread in the meantime.
 */
public interface InboundReviewStore {

    /** Where a held message stands. */
    enum State { HELD, APPROVED, DISCARDED }

    /**
     * One message awaiting a decision.
     *
     * @param source   where it came from, e.g. {@code slack} or {@code hooks/deploy}
     * @param scope    the scope the turn would run under, so approving needs no other context
     * @param thread   the thread the turn would run on
     * @param text     the message as it arrived, unfenced — a reviewer must see what was sent
     * @param severity the worst finding, as a name
     * @param rules    which heuristics fired
     */
    record Held(
            String id,
            String source,
            TurnScope scope,
            ThreadId thread,
            String text,
            String severity,
            List<String> rules,
            State state,
            Instant receivedAt,
            Optional<Instant> decidedAt) {

        public Held {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(severity, "severity");
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(receivedAt, "receivedAt");
            Objects.requireNonNull(decidedAt, "decidedAt");
        }

        public boolean isPending() {
            return state == State.HELD;
        }
    }

    /** Holds a message and returns the record, with its minted id. */
    Held hold(String source, TurnScope scope, ThreadId thread, String text,
            String severity, List<String> rules);

    /** Messages awaiting a decision for a scope's tenant, oldest first. */
    List<Held> pending(TurnScope scope);

    Optional<Held> find(String id);

    /**
     * Records a decision. Returns the record when it was pending and is now decided, empty when
     * the id is unknown or somebody decided it already — so two reviewers cannot both approve.
     */
    Optional<Held> decide(String id, boolean approved, Instant at);
}
