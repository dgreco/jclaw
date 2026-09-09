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

    @Option(names = "--run", description = "Show one run's projection folded from its events, instead of the tail.")
    private String run;

    public StatusCommand(EventLog events) {
        this.events = events;
    }

    @Override
    public Integer call() {
        if (run != null && !run.isBlank()) {
            return projection(new io.jclaw.contracts.turn.TurnRunId(run));
        }
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

    /** The read model of one run: what a UI would show, folded from the same log. */
    private int projection(io.jclaw.contracts.turn.TurnRunId id) {
        List<JclawEvent> all = events.readRun(id).stream().map(EventLog.Entry::event).toList();
        if (all.isEmpty()) {
            System.out.println("(no events for " + id.value() + ")");
            return 1;
        }
        io.jclaw.domain.projection.RunProjection.RunView view =
                io.jclaw.domain.projection.RunProjection.fold(id, all);
        System.out.println("run          " + id.value());
        System.out.println("thread       " + view.scope().map(s -> s.thread().value()).orElse("?"));
        System.out.println("status       " + view.status().map(Enum::name).orElse("?")
                + view.failure().map(f -> " (" + f.category() + ")").orElse(""));
        System.out.println("submitted    " + view.submittedAt().map(Object::toString).orElse("?"));
        System.out.println("finished     " + view.finishedAt().map(Object::toString).orElse("-"));
        System.out.println("model calls  " + view.modelCalls() + " (" + view.modelFailures() + " failed), "
                + view.usage().total() + " tokens");
        System.out.println("iterations   " + view.iterations() + ", checkpoints " + view.checkpoints());
        System.out.println("capabilities " + view.capabilities().size()
                + (view.capabilities().isEmpty() ? "" : ":"));
        view.capabilities().forEach(call -> System.out.printf("    %-28s %-10s %s %dms%n",
                call.capability(), call.effect(), call.outcome(), call.latencyMillis()));
        if (view.injectionFindings() > 0) {
            System.out.println("injection    " + view.injectionFindings() + " finding(s)");
        }
        view.openGate().ifPresent(gate -> System.out.println("open gate    " + gate.kind() + " " + gate.id()));
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
            case JclawEvent.HookFired e -> e.hook() + " " + e.stage() + " " + e.action();
            case JclawEvent.SecretInjected e -> e.capability().value() + " <- secret " + e.secret();
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
