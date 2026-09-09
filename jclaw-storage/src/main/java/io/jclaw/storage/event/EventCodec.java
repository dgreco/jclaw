package io.jclaw.storage.event;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit, hand-written mapping between {@link JclawEvent} and a flat JSON-ready map.
 *
 * <p>Written by hand on purpose, for three reasons that all point the same way:
 *
 * <ul>
 *   <li><b>No reflection.</b> GraalVM native image needs configuration for reflective
 *       serialization; a hand-written codec needs none.</li>
 *   <li><b>No accidental disclosure.</b> Adding a field to an event record cannot silently start
 *       writing it to the log — this file has to change too, which puts it in front of a reviewer.</li>
 *   <li><b>Stable wire format.</b> The on-disk shape is decoupled from the Java record layout, so
 *       refactoring the records does not invalidate existing logs.</li>
 * </ul>
 *
 * <p>Decoding is tolerant: an unknown event type yields empty rather than throwing, so a log
 * written by a newer version stays readable by an older one.
 */
public final class EventCodec {

    private EventCodec() {
    }

    /** Encodes an event to a flat map. */
    public static Map<String, Object> encode(JclawEvent event) {
        Objects.requireNonNull(event, "event");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", event.type());
        out.put("at", event.at().toString());
        out.put("run", event.run().value());

        switch (event) {
            case JclawEvent.TurnSubmitted e -> {
                out.put("tenant", e.scope().tenant());
                out.put("agent", e.scope().agent());
                out.put("project", e.scope().project());
                out.put("thread", e.scope().thread().value());
            }
            case JclawEvent.RunClaimed e -> {
                out.put("workerId", e.workerId());
                out.put("leaseExpiresAt", e.leaseExpiresAt().toString());
            }
            case JclawEvent.ModelCalled e -> {
                out.put("providerId", e.providerId());
                out.put("modelId", e.modelId());
                putUsage(out, e.usage());
                out.put("latencyMillis", e.latencyMillis());
            }
            case JclawEvent.ModelFailed e -> {
                out.put("providerId", e.providerId());
                out.put("category", e.category());
                e.detail().ifPresent(detail -> out.put("detail", detail));
            }
            case JclawEvent.CapabilityInvoked e -> {
                out.put("capability", e.capability().value());
                out.put("effect", e.effect().name());
                out.put("fingerprint", e.fingerprint());
                out.put("outcome", e.outcome());
                out.put("latencyMillis", e.latencyMillis());
            }
            case JclawEvent.InjectionDetected e -> {
                out.put("capability", e.capability().value());
                out.put("severity", e.severity());
                out.put("findings", e.findings());
                out.put("action", e.action());
            }
            case JclawEvent.GateRaised e -> {
                out.put("gate", e.gate().name());
                out.put("gateId", e.gateId());
            }
            case JclawEvent.GateResolved e -> {
                out.put("gateId", e.gateId());
                out.put("approved", e.approved());
            }
            case JclawEvent.CheckpointWritten e -> {
                out.put("kind", e.kind().name());
                out.put("iteration", e.iteration());
            }
            case JclawEvent.RunFinished e -> {
                out.put("status", e.status().name());
                e.failure().ifPresent(failure -> out.put("failure", failure.category()));
                putUsage(out, e.totalUsage());
                out.put("iterations", e.iterations());
            }
        }
        return out;
    }

    /** Decodes a map back to an event, or empty when the type is unknown or fields are missing. */
    public static Optional<JclawEvent> decode(Map<String, Object> record) {
        Objects.requireNonNull(record, "record");
        try {
            String type = str(record, "type");
            Instant at = Instant.parse(str(record, "at"));
            TurnRunId run = new TurnRunId(str(record, "run"));

            return Optional.ofNullable(switch (type) {
                case "turn.submitted" -> new JclawEvent.TurnSubmitted(at, run, new TurnScope(
                        str(record, "tenant"),
                        str(record, "agent"),
                        str(record, "project"),
                        new ThreadId(str(record, "thread"))));

                case "run.claimed" -> new JclawEvent.RunClaimed(
                        at, run, str(record, "workerId"), Instant.parse(str(record, "leaseExpiresAt")));

                case "model.called" -> new JclawEvent.ModelCalled(
                        at, run, str(record, "providerId"), str(record, "modelId"),
                        usage(record), num(record, "latencyMillis"));

                case "model.failed" -> new JclawEvent.ModelFailed(
                        at, run, str(record, "providerId"), str(record, "category"),
                        Optional.ofNullable(record.get("detail")).map(Object::toString));

                case "capability.invoked" -> new JclawEvent.CapabilityInvoked(
                        at, run, CapabilityId.of(str(record, "capability")),
                        EffectClass.valueOf(str(record, "effect")), str(record, "fingerprint"),
                        str(record, "outcome"), num(record, "latencyMillis"));

                case "injection.detected" -> new JclawEvent.InjectionDetected(
                        at, run, CapabilityId.of(str(record, "capability")), str(record, "severity"),
                        (int) num(record, "findings"), str(record, "action"));

                case "gate.raised" -> new JclawEvent.GateRaised(
                        at, run, GateKind.valueOf(str(record, "gate")), str(record, "gateId"));

                case "gate.resolved" -> new JclawEvent.GateResolved(
                        at, run, str(record, "gateId"), bool(record, "approved"));

                case "checkpoint.written" -> new JclawEvent.CheckpointWritten(
                        at, run, CheckpointKind.valueOf(str(record, "kind")), (int) num(record, "iteration"));

                case "run.finished" -> new JclawEvent.RunFinished(
                        at, run, TurnStatus.valueOf(str(record, "status")),
                        Optional.ofNullable(record.get("failure"))
                                .map(Object::toString)
                                .map(FailureKind::fromCategory),
                        usage(record), (int) num(record, "iterations"));

                default -> null; // forward compatibility: unknown types are skipped, not fatal
            });
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void putUsage(Map<String, Object> out, Usage usage) {
        out.put("inputTokens", usage.inputTokens());
        out.put("outputTokens", usage.outputTokens());
        out.put("cacheReadTokens", usage.cacheReadTokens());
        out.put("cacheWriteTokens", usage.cacheWriteTokens());
    }

    private static Usage usage(Map<String, Object> record) {
        return new Usage(
                num(record, "inputTokens"),
                num(record, "outputTokens"),
                num(record, "cacheReadTokens"),
                num(record, "cacheWriteTokens"));
    }

    private static String str(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing field: " + key);
        }
        return value.toString();
    }

    private static long num(Map<String, Object> record, String key) {
        return record.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Map<String, Object> record, String key) {
        return record.get(key) instanceof Boolean b && b;
    }
}
