package io.jclaw.app.runtime;

import io.jclaw.app.observability.ObservedEventLog;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.domain.trigger.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fires event-triggered routines when a matching audit event is written.
 *
 * <p>Listens on the event log rather than on the loop, so a trigger sees exactly what the audit
 * record holds. A matching event enqueues the routine's turn, with a one-line summary of the
 * event appended to the prompt, for a worker to run; nothing executes on the writer's thread.
 *
 * <p>Two rules keep triggers from chasing their own tails: only {@code run.finished} and
 * {@code gate.raised} may drive a trigger, and an event from a run on any routine's thread never
 * fires one, so a routine's own run finishing cannot re-trigger it or a sibling.
 */
@Service
public class EventTriggerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventTriggerDispatcher.class);

    private final RoutineStore routines;
    private final RunStore runs;
    private final JclawRuntime runtime;
    private final Clock clock;

    public EventTriggerDispatcher(RoutineStore routines, RunStore runs, JclawRuntime runtime, EventLog events, Clock clock) {
        this.routines = Objects.requireNonNull(routines, "routines");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (events instanceof ObservedEventLog observed) {
            observed.addListener(this::onEvent);
        } else {
            log.debug("triggers: event log is not observable; event triggers are inactive");
        }
    }

    /** The routines this dispatcher would fire for an event, in scope order. */
    void onEvent(JclawEvent event) {
        String type = event.type();
        if (!Trigger.EVENT_TYPES.contains(type)) {
            return;
        }
        TurnScope scope = runtime.scopeFor(new ThreadId("routines"));
        var all = routines.list(scope);
        if (all.isEmpty()) {
            return;
        }
        Set<String> routineThreads = all.stream().map(r -> r.thread().value()).collect(Collectors.toSet());
        Optional<RunStore.RunRecord> record = runs.find(event.run());
        if (record.isPresent() && routineThreads.contains(record.get().scope().thread().value())) {
            return; // a routine's own run: never a trigger
        }
        Map<String, String> attributes = attributes(event, record);
        for (RoutineStore.Routine routine : all) {
            if (!routine.enabled()) {
                continue;
            }
            Optional<Trigger> trigger = Trigger.parse(routine.trigger()).toOptional();
            if (trigger.isEmpty() || !(trigger.get() instanceof Trigger.OnEvent on) || !on.matches(type, attributes)) {
                continue;
            }
            String summary = attributes.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .sorted()
                    .collect(Collectors.joining(" "));
            routines.recordFiring(routine.id(), clock.instant());
            var run = runtime.enqueue(routine.thread(), ChatMessage.user(
                    routine.prompt() + "\n\nTriggering event: " + type + " " + summary));
            log.debug("triggers: {} fired routine {} on {} -> run {}", type, routine.id().value(),
                    routine.thread().value(), run.value());
        }
    }

    private static Map<String, String> attributes(JclawEvent event, Optional<RunStore.RunRecord> record) {
        Map<String, String> out = new HashMap<>();
        out.put("run", event.run().value());
        record.ifPresent(r -> out.put("thread", r.scope().thread().value()));
        switch (event) {
            case JclawEvent.RunFinished e -> {
                out.put("status", e.status().name());
                e.failure().ifPresent(f -> out.put("failure", f.category()));
                out.put("iterations", String.valueOf(e.iterations()));
            }
            case JclawEvent.GateRaised e -> {
                out.put("kind", e.gate().name());
                out.put("gate", e.gateId());
            }
            default -> { }
        }
        return out;
    }
}
