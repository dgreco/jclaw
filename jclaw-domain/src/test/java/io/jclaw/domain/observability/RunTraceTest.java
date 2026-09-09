package io.jclaw.domain.observability;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTraceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final TurnRunId RUN = new TurnRunId("run_abc");

    private static List<JclawEvent> events() {
        return List.of(
                new JclawEvent.TurnSubmitted(T0, RUN, TurnScope.local("proj", new ThreadId("main"))),
                new JclawEvent.RunClaimed(T0.plusMillis(10), RUN, "w1", T0.plusSeconds(60)),
                new JclawEvent.CheckpointWritten(T0.plusMillis(20), RUN, CheckpointKind.BEFORE_MODEL, 0),
                new JclawEvent.ModelCalled(T0.plusMillis(520), RUN, "mock", "mock-model", Usage.of(10, 5), 500),
                new JclawEvent.CapabilityInvoked(T0.plusMillis(600), RUN, CapabilityId.of("builtin.echo"),
                        EffectClass.PURE, "fp", "ok", 50),
                new JclawEvent.GateRaised(T0.plusMillis(700), RUN, GateKind.APPROVAL, "gate_1"),
                new JclawEvent.GateResolved(T0.plusSeconds(5), RUN, "gate_1", true),
                new JclawEvent.RunFinished(T0.plusSeconds(6), RUN, TurnStatus.COMPLETED, Optional.empty(),
                        Usage.of(10, 5), 1),
                new JclawEvent.ModelCalled(T0, new TurnRunId("run_other"), "mock", "m", Usage.ZERO, 1));
    }

    @Test
    @DisplayName("a run becomes a root span with children for model, capability, and gate, and events for the rest")
    void projectsSpans() {
        List<RunTrace.Span> spans = RunTrace.spans(RUN, events());

        assertEquals(List.of("run", "model.call", "capability.builtin.echo", "gate.approval"),
                spans.stream().map(RunTrace.Span::name).toList());
        RunTrace.Span root = spans.get(0);
        assertEquals(Optional.empty(), root.parentSpanId());
        assertEquals(Duration.ofSeconds(6), root.duration());
        assertEquals("COMPLETED", root.attributes().get("jclaw.status"));
        assertEquals("main", root.attributes().get("jclaw.thread"));
        assertEquals(List.of("run.claimed", "checkpoint"), root.events().stream().map(RunTrace.SpanEvent::name).toList());

        RunTrace.Span model = spans.get(1);
        assertEquals(Optional.of(root.spanId()), model.parentSpanId());
        assertEquals(T0.plusMillis(20), model.start(), "a model span starts latency before the event");
        assertEquals(Duration.ofMillis(500), model.duration());
        assertEquals("10", model.attributes().get("jclaw.tokens.input"));

        RunTrace.Span gate = spans.get(3);
        assertEquals(T0.plusMillis(700), gate.start());
        assertEquals(T0.plusSeconds(5), gate.end());
        assertEquals("true", gate.attributes().get("jclaw.approved"));

        assertEquals(32, RunTrace.traceId(RUN).length());
        assertEquals(RunTrace.spans(RUN, events()), spans, "ids are derived, so a second projection is identical");
        assertTrue(RunTrace.spans(new TurnRunId("run_none"), events()).isEmpty());
    }

    @Test
    @DisplayName("an open gate spans to the last event, and the OTLP shape is well formed")
    @SuppressWarnings("unchecked")
    void openGateAndOtlp() {
        List<JclawEvent> parked = events().subList(0, 6);
        List<RunTrace.Span> spans = RunTrace.spans(RUN, parked);
        RunTrace.Span gate = spans.get(spans.size() - 1);
        assertEquals("gate.approval", gate.name());
        assertEquals("true", gate.attributes().get("jclaw.open"));
        assertEquals(T0.plusMillis(700), gate.end(), "the last event is the gate itself");

        Map<String, Object> otlp = RunTrace.otlp(RUN, events(), "jclaw-test");
        List<Map<String, Object>> resourceSpans = (List<Map<String, Object>>) otlp.get("resourceSpans");
        List<Map<String, Object>> scopeSpans = (List<Map<String, Object>>) resourceSpans.get(0).get("scopeSpans");
        List<Map<String, Object>> otlpSpans = (List<Map<String, Object>>) scopeSpans.get(0).get("spans");
        assertEquals(4, otlpSpans.size());
        Map<String, Object> root = otlpSpans.get(0);
        assertEquals(RunTrace.traceId(RUN), root.get("traceId"));
        assertEquals(String.valueOf(T0.getEpochSecond() * 1_000_000_000L), root.get("startTimeUnixNano"));
        assertEquals(Map.of("code", 1), root.get("status"));
        assertEquals(RunTrace.spans(RUN, events()).get(0).spanId(), otlpSpans.get(1).get("parentSpanId"));
        assertTrue(resourceSpans.get(0).get("resource").toString().contains("jclaw-test"));
    }
}
