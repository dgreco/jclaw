package io.jclaw.contracts.event;

import io.jclaw.contracts.turn.TurnRunId;

import java.util.List;
import java.util.Objects;

/**
 * Append-only log of redacted lifecycle events.
 *
 * <p>Events are observations, not state. The run's authoritative status lives in the process
 * journal; this log exists so a product surface can follow progress and resume from a cursor
 * after disconnecting.
 *
 * <p>Delivery failure here must never corrupt run state — an implementation that cannot append
 * should degrade, not propagate. That is why {@link #append} returns void and is specified not
 * to throw for transient faults.
 */
public interface EventLog {

    /** Opaque replay position. Monotonic within a log. */
    record EventCursor(long position) implements Comparable<EventCursor> {

        public static final EventCursor START = new EventCursor(0);

        public EventCursor {
            if (position < 0) {
                throw new IllegalArgumentException("cursor position must be non-negative");
            }
        }

        public EventCursor next() {
            return new EventCursor(position + 1);
        }

        @Override
        public int compareTo(EventCursor other) {
            return Long.compare(position, other.position);
        }
    }

    /** An event together with its position, as returned by a read. */
    record Entry(EventCursor cursor, JclawEvent event) {
        public Entry {
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(event, "event");
        }
    }

    /**
     * Appends an event. Best effort by contract: implementations log and drop on transient
     * failure rather than failing the run that emitted it.
     */
    void append(JclawEvent event);

    /** Reads forward from {@code after}, exclusive, up to {@code limit} entries. */
    List<Entry> readFrom(EventCursor after, int limit);

    /** Reads every event for one run, in order. */
    List<Entry> readRun(TurnRunId run);

    /** The most recent position, for a caller that wants to tail rather than replay. */
    EventCursor latest();
}
