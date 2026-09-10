// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.domain.observability.RunTrace;
import io.jclaw.domain.projection.RunProjection;
import io.jclaw.storage.projection.RunProjectionCache;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

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

    // Not `--trace`: that name is claimed process-wide by JclawApplication.expandVerbosityFlags,
    // which rewrites it into logging properties and strips it before picocli sees argv. The
    // option existed under that name and could never fire.
    @Option(names = "--spans", description = "With --run: print the run's spans (root, model calls, capabilities, gates).")
    private boolean spans;

    @Option(names = "--run", description = "Show one run's projection folded from its events, instead of the tail.")
    private String run;

    private final RunProjectionCache projections;

    public StatusCommand(EventLog events, RunProjectionCache projections) {
        this.events = events;
        this.projections = projections;
    }

    @Override
    public Integer call() {
        if (run != null && !run.isBlank()) {
            return projection(new TurnRunId(run));
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
    private int projection(TurnRunId id) {
        RunProjection.RunView view = projections.of(id,
                () -> events.readRun(id).stream().map(EventLog.Entry::event).toList());
        if (view.status().isEmpty() && view.lastEventAt().isEmpty()) {
            System.out.println("(no events for " + id.value() + ")");
            return 1;
        }
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
        if (spans) {
            System.out.println();
            System.out.println("trace        " + RunTrace.traceId(id));
            // Spans are not part of the projection, so --spans reads the log whether or not the
            // summary above came from the cache. That is the honest trade: the cache saves the
            // common read, not every read.
            List<JclawEvent> all = events.readRun(id).stream().map(EventLog.Entry::event).toList();
            for (var span : RunTrace.spans(id, all)) {
                System.out.printf("  %s%-30s %6d ms  %s%n", span.parentSpanId().isPresent() ? "  " : "",
                        span.name(), span.duration().toMillis(),
                        span.attributes().entrySet().stream()
                                .filter(e -> !e.getKey().equals("jclaw.run") && !e.getKey().equals("jclaw.fingerprint"))
                                .map(e -> e.getKey().substring("jclaw.".length()) + "=" + e.getValue())
                                .sorted().collect(Collectors.joining(" ")));
                for (var event : span.events()) {
                    System.out.printf("      @%s %s%n", event.at(), event.name());
                }
            }
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
