// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.observability;

import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.turn.TurnRunId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A run's events as a trace: one root span for the run, a child span per model call, capability
 * call, and gate, and span events for the rest.
 *
 * <p>The event log already records every timing a tracer would: a model call's latency, a
 * capability's latency, when a gate was raised and when it was answered. This projection turns
 * them into spans after the fact, so a run is traced without instrumenting the loop and the trace
 * carries exactly what the audit log carries, which is to say nothing a redactor did not pass.
 * Ids are derived from the run id, so the same run yields the same trace every time it is
 * projected, and a trace exported twice deduplicates rather than doubles.
 *
 * <p>{@link #otlp} renders the spans in the OTLP/JSON shape a collector's {@code /v1/traces}
 * accepts, as plain maps: the adapter that speaks HTTP encodes them.
 */
public final class RunTrace {

    /** One span. Times are inclusive start, exclusive end; an instant span has equal times. */
    public record Span(
            String spanId,
            Optional<String> parentSpanId,
            String name,
            Instant start,
            Instant end,
            Map<String, String> attributes,
            List<SpanEvent> events) {

        public Span {
            Objects.requireNonNull(spanId, "spanId");
            Objects.requireNonNull(parentSpanId, "parentSpanId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            attributes = Map.copyOf(attributes);
            events = List.copyOf(events);
        }

        public Duration duration() {
            return Duration.between(start, end);
        }
    }

    /** A point in time on a span. */
    public record SpanEvent(String name, Instant at, Map<String, String> attributes) {
        public SpanEvent {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(at, "at");
            attributes = Map.copyOf(attributes);
        }
    }

    private RunTrace() {
    }

    /** The trace id for a run: 32 hex characters derived from the run id. */
    public static String traceId(TurnRunId run) {
        return hex(digest(run.value()), 16);
    }

    /** The spans of a run, root first, in event order. Empty when the run has no events. */
    public static List<Span> spans(TurnRunId run, List<JclawEvent> events) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(events, "events");
        List<JclawEvent> own = events.stream().filter(e -> e.run().equals(run)).toList();
        if (own.isEmpty()) {
            return List.of();
        }
        String rootId = spanId(run, 0);
        Instant first = own.get(0).at();
        Instant last = own.get(own.size() - 1).at();
        Map<String, String> rootAttributes = new LinkedHashMap<>();
        rootAttributes.put("jclaw.run", run.value());
        List<SpanEvent> rootEvents = new ArrayList<>();
        List<Span> children = new ArrayList<>();
        Map<String, Integer> openGates = new HashMap<>();
        List<JclawEvent.GateRaised> raised = new ArrayList<>();

        int index = 1;
        for (JclawEvent event : own) {
            switch (event) {
                case JclawEvent.TurnSubmitted e -> {
                    rootAttributes.put("jclaw.thread", e.scope().thread().value());
                    rootAttributes.put("jclaw.tenant", e.scope().tenant());
                    rootAttributes.put("jclaw.project", e.scope().project());
                }
                case JclawEvent.RunClaimed e -> rootEvents.add(new SpanEvent("run.claimed", e.at(),
                        Map.of("jclaw.worker", e.workerId())));
                case JclawEvent.ModelCalled e -> children.add(new Span(spanId(run, index++), Optional.of(rootId),
                        "model.call", e.at().minusMillis(e.latencyMillis()), e.at(),
                        Map.of("jclaw.provider", e.providerId(), "jclaw.model", e.modelId(),
                                "jclaw.tokens.input", String.valueOf(e.usage().inputTokens()),
                                "jclaw.tokens.output", String.valueOf(e.usage().outputTokens())),
                        List.of()));
                case JclawEvent.ModelFailed e -> rootEvents.add(new SpanEvent("model.failed", e.at(),
                        Map.of("jclaw.provider", e.providerId(), "jclaw.category", e.category())));
                case JclawEvent.CapabilityInvoked e -> children.add(new Span(spanId(run, index++), Optional.of(rootId),
                        "capability." + e.capability().value(), e.at().minusMillis(e.latencyMillis()), e.at(),
                        Map.of("jclaw.effect", e.effect().name(), "jclaw.outcome", e.outcome(),
                                "jclaw.fingerprint", e.fingerprint()),
                        List.of()));
                case JclawEvent.GateRaised e -> {
                    openGates.put(e.gateId(), raised.size());
                    raised.add(e);
                }
                case JclawEvent.GateResolved e -> {
                    Integer at = openGates.remove(e.gateId());
                    if (at != null) {
                        JclawEvent.GateRaised gate = raised.get(at);
                        children.add(new Span(spanId(run, index++), Optional.of(rootId),
                                "gate." + gate.gate().name().toLowerCase(java.util.Locale.ROOT), gate.at(), e.at(),
                                Map.of("jclaw.gate", e.gateId(), "jclaw.approved", String.valueOf(e.approved())),
                                List.of()));
                    }
                }
                case JclawEvent.CheckpointWritten e -> rootEvents.add(new SpanEvent("checkpoint", e.at(),
                        Map.of("jclaw.kind", e.kind().name(), "jclaw.iteration", String.valueOf(e.iteration()))));
                case JclawEvent.InjectionDetected e -> rootEvents.add(new SpanEvent("injection.detected", e.at(),
                        Map.of("jclaw.capability", e.capability().value(), "jclaw.severity", e.severity(),
                                "jclaw.action", e.action())));
                case JclawEvent.SecretInjected e -> rootEvents.add(new SpanEvent("secret.injected", e.at(),
                        Map.of("jclaw.capability", e.capability().value(), "jclaw.secret", e.secret())));
                case JclawEvent.HookFired e -> rootEvents.add(new SpanEvent("hook.fired", e.at(),
                        Map.of("jclaw.hook", e.hook(), "jclaw.stage", e.stage(), "jclaw.action", e.action())));
                case JclawEvent.RunFinished e -> {
                    rootAttributes.put("jclaw.status", e.status().name());
                    rootAttributes.put("jclaw.iterations", String.valueOf(e.iterations()));
                    rootAttributes.put("jclaw.tokens.total", String.valueOf(e.totalUsage().total()));
                    e.failure().ifPresent(f -> rootAttributes.put("jclaw.failure", f.category()));
                }
            }
        }
        // A gate still open when the log ends is a span that has not ended: it closes at the last event.
        for (Map.Entry<String, Integer> open : openGates.entrySet()) {
            JclawEvent.GateRaised gate = raised.get(open.getValue());
            children.add(new Span(spanId(run, index++), Optional.of(rootId),
                    "gate." + gate.gate().name().toLowerCase(java.util.Locale.ROOT), gate.at(), last,
                    Map.of("jclaw.gate", gate.gateId(), "jclaw.open", "true"), List.of()));
        }

        List<Span> spans = new ArrayList<>();
        spans.add(new Span(rootId, Optional.empty(), "run", first, last, rootAttributes, rootEvents));
        spans.addAll(children);
        return List.copyOf(spans);
    }

    /** The trace in OTLP/JSON shape ({@code resourceSpans} → {@code scopeSpans} → {@code spans}). */
    public static Map<String, Object> otlp(TurnRunId run, List<JclawEvent> events, String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName");
        String traceId = traceId(run);
        List<Map<String, Object>> spans = new ArrayList<>();
        for (Span span : spans(run, events)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("traceId", traceId);
            out.put("spanId", span.spanId());
            span.parentSpanId().ifPresent(parent -> out.put("parentSpanId", parent));
            out.put("name", span.name());
            out.put("kind", 1); // SPAN_KIND_INTERNAL
            out.put("startTimeUnixNano", nanos(span.start()));
            out.put("endTimeUnixNano", nanos(span.end()));
            out.put("attributes", attributes(span.attributes()));
            List<Map<String, Object>> spanEvents = new ArrayList<>();
            for (SpanEvent event : span.events()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", event.name());
                e.put("timeUnixNano", nanos(event.at()));
                e.put("attributes", attributes(event.attributes()));
                spanEvents.add(e);
            }
            out.put("events", spanEvents);
            String status = span.attributes().get("jclaw.status");
            if (status != null) {
                out.put("status", Map.of("code", status.equals("COMPLETED") ? 1 : 2));
            }
            spans.add(out);
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("scope", Map.of("name", "jclaw"));
        scope.put("spans", spans);
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resource", Map.of("attributes", attributes(Map.of("service.name", serviceName))));
        resource.put("scopeSpans", List.of(scope));
        return Map.of("resourceSpans", List.of(resource));
    }

    private static List<Map<String, Object>> attributes(Map<String, String> attributes) {
        List<Map<String, Object>> out = new ArrayList<>();
        attributes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                out.add(Map.of("key", entry.getKey(), "value", Map.of("stringValue", entry.getValue()))));
        return out;
    }

    private static String nanos(Instant at) {
        return String.valueOf(at.getEpochSecond() * 1_000_000_000L + at.getNano());
    }

    /**
     * The span id for one step of a run: 8 bytes derived from the run id and an index.
     *
     * <p>Public because outbound calls need a span id to put in a {@code traceparent} header, and
     * deriving it rather than generating one keeps the property the rest of this class relies on:
     * the same run always produces the same trace, so a trace can be rebuilt from the log after
     * the fact and still match what a collector received while the run was happening.
     */
    public static String spanId(TurnRunId run, int index) {
        return hex(digest(run.value() + ":" + index), 8);
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    private static String hex(byte[] bytes, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(String.format("%02x", bytes[i]));
        }
        return out.toString();
    }
}
