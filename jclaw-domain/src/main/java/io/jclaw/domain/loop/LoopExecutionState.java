package io.jclaw.domain.loop;

import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.turn.TurnRef.LoopGateRef;
import io.jclaw.contracts.turn.TurnRef.LoopMessageRef;
import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.domain.budget.Budget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The complete resumable state of a running loop.
 *
 * <p>Immutable, and total: everything needed to resume after a crash is here, so a checkpoint is
 * just this value serialized. Nothing lives in interpreter locals — if it mattered, it is a field.
 *
 * <p>{@link Phase} is what makes the machine a machine rather than a pile of conditionals. It
 * records which effect the loop is waiting on, so an arriving observation has exactly one valid
 * interpretation and an out-of-order one is detectable rather than silently absorbed.
 */
public record LoopExecutionState(
        Phase phase,
        int iteration,
        List<ChatMessage> messages,
        Budget budget,
        List<LoopMessageRef> assistantRefs,
        List<LoopResultRef> resultRefs,
        Optional<PendingBlock> pendingBlock,
        Optional<ChatMessage> pendingReply,
        int consecutiveModelFailures,
        Optional<CheckpointKind> lastCheckpoint) {

    /** What the loop is currently waiting for. */
    public enum Phase {
        /** Nothing dispatched yet. */
        START,
        /** A model call is in flight. */
        AWAITING_MODEL,
        /** Capability invocations are in flight. */
        AWAITING_CAPABILITIES,
        /** The final assistant message is being written to the transcript. */
        AWAITING_REPLY_PERSIST,
        /** A checkpoint write is in flight before blocking. */
        AWAITING_CHECKPOINT,
        /**
         * Rehydrated from a checkpoint and not yet re-driven. A distinct phase because a resumed
         * run is mid-flight: replaying {@code Start} into it would be a protocol violation, and
         * guessing its previous phase would be worse.
         */
        RESUMING,
        /** Terminal. The machine emits no further decisions. */
        DONE
    }

    /** A gate observed from a capability outcome, held until its checkpoint ref is minted. */
    public record PendingBlock(GateKind gate, LoopGateRef gateRef) {
        public PendingBlock {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(gateRef, "gateRef");
        }
    }

    public LoopExecutionState {
        Objects.requireNonNull(phase, "phase");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(budget, "budget");
        assistantRefs = List.copyOf(Objects.requireNonNull(assistantRefs, "assistantRefs"));
        resultRefs = List.copyOf(Objects.requireNonNull(resultRefs, "resultRefs"));
        Objects.requireNonNull(pendingBlock, "pendingBlock");
        Objects.requireNonNull(pendingReply, "pendingReply");
        Objects.requireNonNull(lastCheckpoint, "lastCheckpoint");
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must be non-negative");
        }
    }

    /** Initial state for a fresh run seeded with the user's message. */
    public static LoopExecutionState start(List<ChatMessage> seed, Budget budget) {
        return new LoopExecutionState(
                Phase.START,
                0,
                List.copyOf(seed),
                budget,
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty());
    }

    // --- Transitions. Each returns a new value; none mutate. ---

    public LoopExecutionState withPhase(Phase next) {
        return new LoopExecutionState(next, iteration, messages, budget, assistantRefs, resultRefs,
                pendingBlock, pendingReply, consecutiveModelFailures, lastCheckpoint);
    }

    public LoopExecutionState withMessageAppended(ChatMessage message) {
        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(message);
        return new LoopExecutionState(phase, iteration, next, budget, assistantRefs, resultRefs,
                pendingBlock, pendingReply, consecutiveModelFailures, lastCheckpoint);
    }

    public LoopExecutionState withBudget(Budget next) {
        return new LoopExecutionState(phase, iteration, messages, next, assistantRefs, resultRefs,
                pendingBlock, pendingReply, consecutiveModelFailures, lastCheckpoint);
    }

    public LoopExecutionState withIterationAdvanced() {
        return new LoopExecutionState(phase, iteration + 1, messages, budget.nextIteration(),
                assistantRefs, resultRefs, pendingBlock, pendingReply, consecutiveModelFailures, lastCheckpoint);
    }

    public LoopExecutionState withAssistantRef(LoopMessageRef ref) {
        List<LoopMessageRef> next = new ArrayList<>(assistantRefs);
        next.add(ref);
        return new LoopExecutionState(phase, iteration, messages, budget, next, resultRefs,
                pendingBlock, pendingReply, consecutiveModelFailures, lastCheckpoint);
    }

    public LoopExecutionState withResultRefs(List<LoopResultRef> refs) {
        List<LoopResultRef> next = new ArrayList<>(resultRefs);
        next.addAll(refs);
        return new LoopExecutionState(phase, iteration, messages, budget, assistantRefs, next,
                pendingBlock, pendingReply, consecutiveModelFailures, lastCheckpoint);
    }

    /** Clears a resolved gate. Called on resume once a human has decided. */
    public LoopExecutionState withoutPendingBlock() {
        return new LoopExecutionState(phase, iteration, messages, budget, assistantRefs, resultRefs,
                Optional.empty(), pendingReply, consecutiveModelFailures, lastCheckpoint);
    }

    public LoopExecutionState withPendingBlock(PendingBlock block) {
        return new LoopExecutionState(phase, iteration, messages, budget, assistantRefs, resultRefs,
                Optional.of(block), pendingReply, consecutiveModelFailures, lastCheckpoint);
    }

    public LoopExecutionState withPendingReply(ChatMessage reply) {
        return new LoopExecutionState(phase, iteration, messages, budget, assistantRefs, resultRefs,
                pendingBlock, Optional.of(reply), consecutiveModelFailures, lastCheckpoint);
    }

    public LoopExecutionState withModelFailure() {
        return new LoopExecutionState(phase, iteration, messages, budget, assistantRefs, resultRefs,
                pendingBlock, pendingReply, consecutiveModelFailures + 1, lastCheckpoint);
    }

    public LoopExecutionState withModelSuccess() {
        return new LoopExecutionState(phase, iteration, messages, budget, assistantRefs, resultRefs,
                pendingBlock, pendingReply, 0, lastCheckpoint);
    }

    public LoopExecutionState withCheckpoint(CheckpointKind kind) {
        return new LoopExecutionState(phase, iteration, messages, budget, assistantRefs, resultRefs,
                pendingBlock, pendingReply, consecutiveModelFailures, Optional.of(kind));
    }

    /** Tool calls requested by the most recent assistant message, if any. */
    public List<ContentBlock.ToolUse> outstandingToolUses() {
        if (messages.isEmpty()) {
            return List.of();
        }
        ChatMessage last = messages.get(messages.size() - 1);
        return last.role() == ChatMessage.Role.ASSISTANT ? last.toolUses() : List.of();
    }

    public boolean isTerminal() {
        return phase == Phase.DONE;
    }
}
