// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.observability;

import io.jclaw.ports.event.EventLog.EventCursor;
import io.jclaw.ports.event.EventLog;
import io.jclaw.ports.event.JclawEvent;
import io.jclaw.ports.turn.TurnRunId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The event log, with metrics and trace export riding on every append.
 *
 * <p>Wrapping the log rather than instrumenting the loop, kernel, and runtime keeps those modules
 * free of any telemetry concern and makes one guarantee: whatever is observed was first written
 * to the audit log. An event that failed to persist is never counted, and a run's trace is
 * exported only once its {@code run.finished} is durable.
 */
public final class ObservedEventLog implements EventLog {

    private final EventLog delegate;
    private final Telemetry telemetry;
    private final Optional<OtlpExporter> exporter;
    private final List<Consumer<JclawEvent>> listeners =
            new CopyOnWriteArrayList<>();

    public ObservedEventLog(EventLog delegate, Telemetry telemetry, Optional<OtlpExporter> exporter) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
    }

    /** Registers a listener called after every event is durable. Listeners must not block. */
    public void addListener(Consumer<JclawEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void append(JclawEvent event) {
        delegate.append(event);
        telemetry.record(event);
        listeners.forEach(listener -> listener.accept(event));
        if (event instanceof JclawEvent.RunFinished finished) {
            exporter.ifPresent(e -> e.export(finished.run(),
                    delegate.readRun(finished.run()).stream().map(Entry::event).toList()));
        }
    }

    @Override
    public List<Entry> readFrom(EventCursor after, int limit) {
        return delegate.readFrom(after, limit);
    }

    @Override
    public List<Entry> readRun(TurnRunId run) {
        return delegate.readRun(run);
    }

    @Override
    public EventCursor latest() {
        return delegate.latest();
    }
}
