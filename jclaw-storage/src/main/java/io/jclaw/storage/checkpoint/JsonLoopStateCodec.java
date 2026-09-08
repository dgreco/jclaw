package io.jclaw.storage.checkpoint;

import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.TurnRef.LoopGateRef;
import io.jclaw.contracts.turn.TurnRef.LoopMessageRef;
import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.domain.budget.Budget;
import io.jclaw.domain.loop.LoopExecutionState;
import io.jclaw.domain.loop.LoopStateCodec;
import io.jclaw.storage.thread.MessageCodec;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON codec for {@link LoopExecutionState}, used as the checkpoint payload.
 *
 * <p>Explicit field mapping again, for the same reasons as the event and message codecs: no
 * reflection for native image, and a wire format that survives refactoring the record. Here there
 * is a third reason — a checkpoint written by one version may be read by another, and an explicit
 * codec makes that compatibility surface visible instead of emergent.
 *
 * <p>{@link #decode} refuses a payload whose schema version it does not recognise. Misreading old
 * state would resume a run into a subtly wrong position; failing loudly is the only safe option.
 */
public final class JsonLoopStateCodec implements LoopStateCodec {

    /** The schema version this codec reads and writes. */
    public static final int SCHEMA_VERSION = 1;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public byte[] encode(LoopExecutionState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", state.phase().name());
        out.put("iteration", state.iteration());
        out.put("consecutiveModelFailures", state.consecutiveModelFailures());

        out.put("messages", state.messages().stream().map(MessageCodec::encode).toList());
        out.put("assistantRefs", state.assistantRefs().stream().map(LoopMessageRef::value).toList());
        out.put("resultRefs", state.resultRefs().stream().map(LoopResultRef::value).toList());

        Budget budget = state.budget();
        Map<String, Object> budgetOut = new LinkedHashMap<>();
        budgetOut.put("maxTokens", budget.maxTokens());
        budgetOut.put("maxIterations", budget.maxIterations());
        budgetOut.put("wallClockMillis", budget.wallClock().toMillis());
        budgetOut.put("startedAt", budget.startedAt().toString());
        budgetOut.put("inputTokens", budget.spent().inputTokens());
        budgetOut.put("outputTokens", budget.spent().outputTokens());
        budgetOut.put("cacheReadTokens", budget.spent().cacheReadTokens());
        budgetOut.put("cacheWriteTokens", budget.spent().cacheWriteTokens());
        budgetOut.put("iterations", budget.iterations());
        out.put("budget", budgetOut);

        state.pendingBlock().ifPresent(block -> {
            Map<String, Object> blockOut = new LinkedHashMap<>();
            blockOut.put("gate", block.gate().name());
            blockOut.put("gateRef", block.gateRef().value());
            out.put("pendingBlock", blockOut);
        });
        state.pendingReply().ifPresent(reply -> out.put("pendingReply", MessageCodec.encode(reply)));
        state.lastCheckpoint().ifPresent(kind -> out.put("lastCheckpoint", kind.name()));

        return mapper.writeValueAsString(out).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @SuppressWarnings("unchecked")
    public LoopExecutionState decode(byte[] payload, int schemaVersion) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported checkpoint schema version " + schemaVersion
                            + "; this build reads version " + SCHEMA_VERSION);
        }
        Map<String, Object> in = mapper.readValue(
                new String(payload, StandardCharsets.UTF_8), Map.class);

        List<ChatMessage> messages = new ArrayList<>();
        if (in.get("messages") instanceof List<?> raw) {
            for (Object element : raw) {
                if (element instanceof Map<?, ?> map) {
                    messages.add(MessageCodec.decode((Map<String, Object>) map));
                }
            }
        }

        Map<String, Object> budgetIn = (Map<String, Object>) in.get("budget");
        Budget budget = new Budget(
                asLong(budgetIn.get("maxTokens")),
                (int) asLong(budgetIn.get("maxIterations")),
                Duration.ofMillis(asLong(budgetIn.get("wallClockMillis"))),
                Instant.parse(String.valueOf(budgetIn.get("startedAt"))),
                new Usage(
                        asLong(budgetIn.get("inputTokens")),
                        asLong(budgetIn.get("outputTokens")),
                        asLong(budgetIn.get("cacheReadTokens")),
                        asLong(budgetIn.get("cacheWriteTokens"))),
                (int) asLong(budgetIn.get("iterations")));

        Optional<LoopExecutionState.PendingBlock> pendingBlock = Optional.empty();
        if (in.get("pendingBlock") instanceof Map<?, ?> blockIn) {
            pendingBlock = Optional.of(new LoopExecutionState.PendingBlock(
                    GateKind.valueOf(String.valueOf(blockIn.get("gate"))),
                    new LoopGateRef(String.valueOf(blockIn.get("gateRef")))));
        }

        Optional<ChatMessage> pendingReply = in.get("pendingReply") instanceof Map<?, ?> replyIn
                ? Optional.of(MessageCodec.decode((Map<String, Object>) replyIn))
                : Optional.empty();

        Optional<CheckpointKind> lastCheckpoint = in.get("lastCheckpoint") instanceof String kind
                ? Optional.of(CheckpointKind.valueOf(kind))
                : Optional.empty();

        return new LoopExecutionState(
                LoopExecutionState.Phase.valueOf(String.valueOf(in.get("phase"))),
                (int) asLong(in.get("iteration")),
                messages,
                budget,
                stringList(in.get("assistantRefs")).stream().map(LoopMessageRef::new).toList(),
                stringList(in.get("resultRefs")).stream().map(LoopResultRef::new).toList(),
                pendingBlock,
                pendingReply,
                (int) asLong(in.get("consecutiveModelFailures")),
                lastCheckpoint);
    }


    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
