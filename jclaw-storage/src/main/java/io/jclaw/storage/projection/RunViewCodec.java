package io.jclaw.storage.projection;

import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.projection.RunProjection.CapabilityCall;
import io.jclaw.domain.projection.RunProjection.Gate;
import io.jclaw.domain.projection.RunProjection.RunView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A run view to and from a flat map, by hand.
 *
 * <p>Hand-written for the reasons every other codec here is: no reflection for the native image,
 * no field disclosed by accident, and a wire format that does not move when a record does.
 * Adding a component to {@link RunView} means editing this, which is the point — a materialised
 * projection that silently loses a field would be worse than one that is always folded.
 *
 * <p>{@link #decode} is total: anything it cannot read becomes empty or zero rather than an
 * exception. A cached projection is an optimisation, and an optimisation that can throw is a
 * liability — a row written by an older build must degrade to a miss, never to a failed request.
 */
public final class RunViewCodec {

    /** Bumped when the shape changes; a row at another version is ignored and refolded. */
    public static final int VERSION = 1;

    private RunViewCodec() {
    }

    public static Map<String, Object> encode(RunView view) {
        Objects.requireNonNull(view, "view");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", VERSION);
        out.put("run", view.run().value());
        view.scope().ifPresent(scope -> {
            out.put("tenant", scope.tenant());
            out.put("agent", scope.agent());
            out.put("project", scope.project());
            out.put("thread", scope.thread().value());
        });
        view.status().ifPresent(status -> out.put("status", status.name()));
        view.submittedAt().ifPresent(at -> out.put("submittedAt", at.toString()));
        view.finishedAt().ifPresent(at -> out.put("finishedAt", at.toString()));
        view.lastEventAt().ifPresent(at -> out.put("lastEventAt", at.toString()));
        out.put("modelCalls", view.modelCalls());
        out.put("modelFailures", view.modelFailures());
        out.put("inputTokens", view.usage().inputTokens());
        out.put("outputTokens", view.usage().outputTokens());
        out.put("cacheReadTokens", view.usage().cacheReadTokens());
        out.put("cacheWriteTokens", view.usage().cacheWriteTokens());
        List<Object> calls = new ArrayList<>();
        for (CapabilityCall call : view.capabilities()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("capability", call.capability());
            encoded.put("effect", call.effect());
            encoded.put("outcome", call.outcome());
            encoded.put("latencyMillis", call.latencyMillis());
            calls.add(encoded);
        }
        out.put("capabilities", calls);
        List<Object> gates = new ArrayList<>();
        for (Gate gate : view.gates()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("kind", gate.kind().name());
            encoded.put("id", gate.id());
            gate.approved().ifPresent(approved -> encoded.put("approved", approved));
            gates.add(encoded);
        }
        out.put("gates", gates);
        out.put("injectionFindings", view.injectionFindings());
        out.put("checkpoints", view.checkpoints());
        out.put("iterations", view.iterations());
        view.failure().ifPresent(failure -> out.put("failure", failure.name()));
        return out;
    }

    /** Reads a row back, or empty if it is from another version or cannot be read. */
    @SuppressWarnings("unchecked")
    public static Optional<RunView> decode(Map<String, Object> row) {
        Objects.requireNonNull(row, "row");
        if (!(row.get("v") instanceof Number version) || version.intValue() != VERSION) {
            return Optional.empty();
        }
        try {
            Optional<TurnScope> scope = row.get("thread") instanceof String thread
                    ? Optional.of(new TurnScope(text(row, "tenant", "local"), text(row, "agent", "default"),
                            text(row, "project", "default"), new ThreadId(thread)))
                    : Optional.empty();

            List<CapabilityCall> calls = new ArrayList<>();
            if (row.get("capabilities") instanceof List<?> encoded) {
                for (Object element : encoded) {
                    Map<String, Object> call = (Map<String, Object>) element;
                    calls.add(new CapabilityCall(
                            text(call, "capability", ""), text(call, "effect", ""),
                            text(call, "outcome", ""), number(call, "latencyMillis")));
                }
            }
            List<Gate> gates = new ArrayList<>();
            if (row.get("gates") instanceof List<?> encoded) {
                for (Object element : encoded) {
                    Map<String, Object> gate = (Map<String, Object>) element;
                    gates.add(new Gate(
                            GateKind.valueOf(text(gate, "kind", "")), text(gate, "id", ""),
                            gate.get("approved") instanceof Boolean approved
                                    ? Optional.of(approved) : Optional.empty()));
                }
            }
            return Optional.of(new RunView(
                    new TurnRunId(text(row, "run", "")),
                    scope,
                    row.get("status") instanceof String status
                            ? Optional.of(TurnStatus.valueOf(status)) : Optional.empty(),
                    instant(row, "submittedAt"),
                    instant(row, "finishedAt"),
                    instant(row, "lastEventAt"),
                    (int) number(row, "modelCalls"),
                    (int) number(row, "modelFailures"),
                    new Usage(number(row, "inputTokens"), number(row, "outputTokens"),
                            number(row, "cacheReadTokens"), number(row, "cacheWriteTokens")),
                    calls,
                    gates,
                    (int) number(row, "injectionFindings"),
                    (int) number(row, "checkpoints"),
                    (int) number(row, "iterations"),
                    row.get("failure") instanceof String failure
                            ? Optional.of(FailureKind.valueOf(failure)) : Optional.empty()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String text(Map<String, Object> row, String key, String fallback) {
        return row.get(key) instanceof String value ? value : fallback;
    }

    private static long number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number value ? value.longValue() : 0L;
    }

    private static Optional<Instant> instant(Map<String, Object> row, String key) {
        if (!(row.get(key) instanceof String value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
