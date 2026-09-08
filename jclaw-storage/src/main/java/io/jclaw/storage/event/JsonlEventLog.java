package io.jclaw.storage.event;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.storage.jsonl.JsonlFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable {@link EventLog} over an append-only JSONL file.
 *
 * <p>Honours the contract's "delivery failure must not corrupt run state" rule literally:
 * {@link #append} swallows I/O failure rather than propagating it. A run must not fail because the
 * observability sidecar could not write — the log is a witness to the work, not a participant in it.
 *
 * <p>Cursors are line positions. Simple, monotonic, and meaningful across restarts because the file
 * is append-only and lines are never rewritten.
 */
public final class JsonlEventLog implements EventLog {

    private static final Logger log = LoggerFactory.getLogger(JsonlEventLog.class);

    private final JsonlFile file;

    public JsonlEventLog(JsonlFile file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public void append(JclawEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            file.append(EventCodec.encode(event));
            log.trace("event appended: {} (run {})", event.getClass().getSimpleName(), event.run().value());
        } catch (RuntimeException e) {
            // Best effort by contract. Losing an event is strictly better than failing the run
            // that produced it.
            log.debug("event log write failed ({}); event {} dropped",
                    e.getClass().getSimpleName(), event.getClass().getSimpleName());
        }
    }

    @Override
    public List<Entry> readFrom(EventCursor after, int limit) {
        Objects.requireNonNull(after, "after");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        List<Entry> entries = new ArrayList<>();
        List<Map<String, Object>> records = file.readAll();
        for (int position = 0; position < records.size() && entries.size() < limit; position++) {
            if (position < after.position()) {
                continue;
            }
            EventCursor cursor = new EventCursor(position);
            EventCodec.decode(records.get(position))
                    .ifPresent(event -> entries.add(new Entry(cursor, event)));
        }
        return entries;
    }

    @Override
    public List<Entry> readRun(TurnRunId run) {
        Objects.requireNonNull(run, "run");
        List<Entry> entries = new ArrayList<>();
        List<Map<String, Object>> records = file.readAll();
        for (int position = 0; position < records.size(); position++) {
            EventCursor cursor = new EventCursor(position);
            EventCodec.decode(records.get(position))
                    .filter(event -> event.run().equals(run))
                    .ifPresent(event -> entries.add(new Entry(cursor, event)));
        }
        return entries;
    }

    @Override
    public EventCursor latest() {
        return new EventCursor(file.size());
    }
}
