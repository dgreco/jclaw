package io.jclaw.app.cli;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Recent activity from the event log.
 *
 * <p>Reads the redacted log rather than any internal state, so what an operator sees here is
 * exactly what was durably recorded — no richer, no poorer. If something is missing from this
 * output it is missing from the audit trail, which is a bug worth seeing.
 */
@Component
@Command(
        name = "status",
        description = "Show recent runs and activity from the event log.",
        mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    private final EventLog events;

    @Option(names = {"-n", "--limit"}, description = "Number of events to show. Default 20.")
    private int limit = 20;

    public StatusCommand(EventLog events) {
        this.events = events;
    }

    @Override
    public Integer call() {
        EventLog.EventCursor latest = events.latest();
        long from = Math.max(0, latest.position() - limit);
        List<EventLog.Entry> entries = events.readFrom(new EventLog.EventCursor(from), limit);

        if (entries.isEmpty()) {
            System.out.println("(no activity recorded yet)");
            return 0;
        }

        for (EventLog.Entry entry : entries) {
            JclawEvent event = entry.event();
            System.out.printf("%s  %-20s  %s  %s%n",
                    event.at(),
                    event.type(),
                    event.run().value(),
                    detail(event));
        }
        return 0;
    }

    /** One-line detail per event type. Only redacted fields exist to print. */
    private static String detail(JclawEvent event) {
        return switch (event) {
            case JclawEvent.TurnSubmitted e -> e.scope().lockKey();
            case JclawEvent.RunClaimed e -> "worker=" + e.workerId();
            case JclawEvent.ModelCalled e -> e.modelId()
                    + " in=" + e.usage().inputTokens()
                    + " out=" + e.usage().outputTokens()
                    + " " + e.latencyMillis() + "ms";
            case JclawEvent.ModelFailed e -> e.providerId() + " " + e.category()
                    + e.detail().map(d -> " (" + d + ")").orElse("");
            case JclawEvent.CapabilityInvoked e -> e.capability().value()
                    + " [" + e.effect() + "] " + e.outcome() + " " + e.latencyMillis() + "ms";
            case JclawEvent.InjectionDetected e -> e.capability().value()
                    + " " + e.severity() + " x" + e.findings() + " " + e.action();
            case JclawEvent.GateRaised e -> e.gate() + " " + e.gateId();
            case JclawEvent.GateResolved e -> e.gateId() + " approved=" + e.approved();
            case JclawEvent.CheckpointWritten e -> e.kind() + " iteration=" + e.iteration();
            case JclawEvent.RunFinished e -> e.status()
                    + e.failure().map(f -> " (" + f.category() + ")").orElse("")
                    + " tokens=" + e.totalUsage().total();
        };
    }
}
