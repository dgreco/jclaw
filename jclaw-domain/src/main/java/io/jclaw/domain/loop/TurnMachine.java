package io.jclaw.domain.loop;

import io.jclaw.contracts.capability.CapabilityOutcome;
import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.loop.LoopExit;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.domain.budget.Budget;
import io.jclaw.domain.loop.LoopExecutionState.Phase;
import io.jclaw.domain.prompt.ContextCompaction;
import io.jclaw.domain.prompt.ContextSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pure turn machine.
 *
 * <p>One total function: {@code (state, observation, policy, now) -> (state, decision)}. It
 * performs no I/O, opens no sockets, reads no clock, and holds no ports. Everything
 * non-deterministic arrives as an {@link Observation}; everything it wants done leaves as a
 * {@link LoopDecision}. The interpreter in {@code jclaw-loop} is the only component that touches
 * the world.
 *
 * <p>Two consequences worth stating plainly. First, the agent's entire control flow is testable
 * with plain values and no test doubles — the tests for this class construct responses by hand and
 * assert on decisions. Second, replay is exact: feeding a recorded observation sequence back
 * reproduces the run's decisions byte for byte, which is what makes a checkpoint trustworthy.
 *
 * <p>The machine is also defensive about its own protocol. An observation that does not match the
 * current {@link Phase} is not ignored and not tolerated — it terminates the run with
 * {@link FailureKind#DRIVER_PROTOCOL_VIOLATION}, because an interpreter delivering effects out of
 * order is a bug that must surface loudly rather than corrupt a transcript quietly.
 */
public final class TurnMachine {

    private TurnMachine() {
    }

    /** The machine's output: the next state paired with what to do. */
    public record LoopStep(LoopExecutionState state, LoopDecision decision) {
        public LoopStep {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(decision, "decision");
        }
    }

    /**
     * Advances the machine by one observation.
     *
     * @param now current time, supplied rather than read so the function stays pure and budget
     *            exhaustion is reproducible
     */
    public static LoopStep step(
            LoopExecutionState state, Observation observation, LoopPolicy policy, Instant now) {

        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");

        // Cancellation is honoured from any phase. The interpreter only delivers it between
        // effects, so stopping here cannot orphan an in-flight capability.
        if (observation instanceof Observation.CancelRequested) {
            return finish(state, LoopExit.Cancelled.withoutCheckpoint());
        }

        return switch (state.phase()) {
            case START -> onStart(state, observation, now);
            case RESUMING -> onResumed(state, observation, now);
            case AWAITING_CHECKPOINT -> onCheckpointed(state, observation, policy);
            case AWAITING_MODEL -> onModel(state, observation, policy, now);
            case AWAITING_SUMMARY -> onSummary(state, observation, policy);
            case AWAITING_CAPABILITIES -> onCapabilities(state, observation, now);
            case AWAITING_REPLY_PERSIST -> onReplyPersisted(state, observation);
            case DONE -> protocolViolation(state, "observation after terminal state");
        };
    }

    // --- Phase handlers ---

    private static LoopStep onStart(LoopExecutionState state, Observation observation, Instant now) {

        if (!(observation instanceof Observation.Start)) {
            return protocolViolation(state, "expected Start");
        }
        Budget.Exhaustion exhausted = state.budget().exhaustion(now);
        if (exhausted != Budget.Exhaustion.NONE) {
            return finish(state, LoopExit.Failed.of(FailureKind.BUDGET_EXHAUSTED, exhausted.reason()));
        }
        return checkpointThenModel(state);
    }

    /**
     * Re-drives a run rehydrated from a checkpoint.
     *
     * <p>If the last assistant message still has outstanding tool calls, those are re-dispatched:
     * the run parked because one of them needed approval, and the approval is now decided. The
     * kernel re-authorizes each invocation against the (possibly granted) exact-invocation
     * fingerprint, so resuming never assumes authority — it asks again and gets a different answer.
     *
     * <p>Otherwise the run continues to the next model call.
     */
    private static LoopStep onResumed(
            LoopExecutionState state, Observation observation, Instant now) {

        if (!(observation instanceof Observation.Resumed)) {
            return protocolViolation(state, "expected Resumed");
        }
        Budget.Exhaustion exhausted = state.budget().exhaustion(now);
        if (exhausted != Budget.Exhaustion.NONE) {
            return finish(state, LoopExit.Failed.of(FailureKind.BUDGET_EXHAUSTED, exhausted.reason()));
        }

        List<ContentBlock.ToolUse> outstanding = state.outstandingToolUses();
        if (!outstanding.isEmpty()) {
            return new LoopStep(
                    state.withoutPendingBlock().withPhase(Phase.AWAITING_CAPABILITIES),
                    new LoopDecision.InvokeCapabilities(outstanding));
        }
        return checkpointThenModel(state.withoutPendingBlock());
    }

    private static LoopStep onCheckpointed(
            LoopExecutionState state, Observation observation, LoopPolicy policy) {

        if (!(observation instanceof Observation.Checkpointed checkpointed)) {
            return protocolViolation(state, "expected Checkpointed");
        }
        LoopExecutionState recorded = state.withCheckpoint(checkpointed.kind());

        // The checkpoint kind says what the loop parked in front of, so it also says what
        // happens next. No extra flag needed.
        return switch (checkpointed.kind()) {
            case BEFORE_MODEL -> summariseOrCall(recorded, policy);

            case BEFORE_BLOCK -> recorded.pendingBlock()
                    .map(block -> finish(recorded, new LoopExit.Blocked(
                            block.gate(), block.gateRef(), checkpointed.ref())))
                    .orElseGet(() -> protocolViolation(recorded, "BEFORE_BLOCK checkpoint without a pending gate"));

            case BEFORE_CAPABILITY, AFTER_CAPABILITY, AFTER_MODEL, UNKNOWN ->
                    protocolViolation(recorded, "unexpected checkpoint kind " + checkpointed.kind());
        };
    }

    /**
     * Before a model call: if the context policy would drop history and asks for summaries, ask
     * the model to summarise the dropped span first; otherwise call the model directly.
     *
     * <p>The summary call is itself an effect, decided here and performed by the interpreter like
     * any other. It is not user-facing, so nothing streams from it, and it offers no tools.
     */
    private static LoopStep summariseOrCall(LoopExecutionState state, LoopPolicy policy) {
        if (policy.context().summarise()) {
            ContextCompaction.Compacted view =
                    ContextCompaction.compact(state.messages(), policy.context());
            Optional<ModelRequest> summary = ContextSummary.request(
                    policy.model(), view, policy.context().summaryMaxTokens());
            if (summary.isPresent()) {
                return new LoopStep(
                        state.withPhase(Phase.AWAITING_SUMMARY),
                        new LoopDecision.CallModel(summary.get(), false));
            }
        }
        return new LoopStep(
                state.withPhase(Phase.AWAITING_MODEL),
                new LoopDecision.CallModel(buildRequest(state, policy)));
    }

    /**
     * The summary came back (or did not). Either way the real model call follows: with the
     * dropped span replaced by the summary, or, if the summary failed, with plain truncation.
     * A failed summary never fails the run; the truncation path is the baseline it improves on.
     */
    private static LoopStep onSummary(
            LoopExecutionState state, Observation observation, LoopPolicy policy) {

        return switch (observation) {
            case Observation.ModelReplied replied -> {
                LoopExecutionState charged = state
                        .withBudget(state.budget().charge(replied.response().usage()))
                        .withModelSuccess();
                ContextCompaction.Compacted view =
                        ContextCompaction.compact(charged.messages(), policy.context());
                LoopExecutionState summarised = charged.withMessages(
                        ContextSummary.apply(view, replied.response().text()));
                yield new LoopStep(
                        summarised.withPhase(Phase.AWAITING_MODEL),
                        new LoopDecision.CallModel(buildRequest(summarised, policy)));
            }
            case Observation.ModelFailed ignored -> new LoopStep(
                    state.withPhase(Phase.AWAITING_MODEL),
                    new LoopDecision.CallModel(buildRequest(state, policy)));
            case Observation.AuthRequired auth -> onAuthRequired(state, auth);
            default -> protocolViolation(state, "expected a summary reply");
        };
    }

    private static LoopStep onModel(
            LoopExecutionState state, Observation observation, LoopPolicy policy, Instant now) {

        return switch (observation) {
            case Observation.ModelReplied replied -> onModelReplied(state, replied);
            case Observation.ModelFailed failed -> onModelFailed(state, failed, policy, now);
            case Observation.AuthRequired auth -> onAuthRequired(state, auth);
            default -> protocolViolation(state, "expected ModelReplied, ModelFailed, or AuthRequired");
        };
    }

    /**
     * Parks the run on an auth gate.
     *
     * <p>Nothing was appended: the model produced nothing, so the checkpoint written next is the
     * same replay-safe {@code BEFORE_BLOCK} an approval gate uses, and a resume goes straight back
     * to the model call with the state it had.
     */
    private static LoopStep onAuthRequired(LoopExecutionState state, Observation.AuthRequired auth) {
        LoopExecutionState blocked = state.withPendingBlock(
                new LoopExecutionState.PendingBlock(GateKind.AUTH, auth.gateRef()));
        return new LoopStep(
                blocked.withPhase(Phase.AWAITING_CHECKPOINT),
                new LoopDecision.Checkpoint(CheckpointKind.BEFORE_BLOCK));
    }

    private static LoopStep onModelReplied(LoopExecutionState state, Observation.ModelReplied replied) {

        ModelResponse response = replied.response();
        LoopExecutionState charged = state
                .withBudget(state.budget().charge(response.usage()))
                .withModelSuccess()
                .withMessageAppended(response.message());

        List<ContentBlock.ToolUse> toolUses = response.toolUses();
        if (!toolUses.isEmpty()) {
            // The model wants tools. Budget is checked before the next model call rather than
            // here, so an in-flight tool round always completes and its results are recorded.
            return new LoopStep(
                    charged.withPhase(Phase.AWAITING_CAPABILITIES),
                    new LoopDecision.InvokeCapabilities(toolUses));
        }

        // A plain reply ends the turn — but only once the host has minted a ref for it.
        return new LoopStep(
                charged.withPendingReply(response.message()).withPhase(Phase.AWAITING_REPLY_PERSIST),
                new LoopDecision.PersistReply(response.message(), false));
    }

    private static LoopStep onModelFailed(
            LoopExecutionState state, Observation.ModelFailed failed, LoopPolicy policy, Instant now) {

        LoopExecutionState failedState = state.withModelFailure();
        boolean budgetLeft = !failedState.budget().isExhausted(now);
        boolean retriesLeft = failedState.consecutiveModelFailures() <= policy.maxConsecutiveModelFailures();

        if (failed.retryable() && retriesLeft && budgetLeft) {
            return checkpointThenModel(failedState);
        }
        return finish(failedState, new LoopExit.Failed(failed.kind(), failed.detail()));
    }

    private static LoopStep onCapabilities(
            LoopExecutionState state, Observation observation, Instant now) {

        if (!(observation instanceof Observation.CapabilitiesCompleted completed)) {
            return protocolViolation(state, "expected CapabilitiesCompleted");
        }

        // A gate anywhere in the batch parks the whole run. Partial progress is preserved in the
        // checkpoint, so resuming re-dispatches only what has not been authorized.
        Optional<CapabilityOutcome.NeedsApproval> gate = completed.outcomes().stream()
                .map(Observation.CallOutcome::outcome)
                .filter(CapabilityOutcome.NeedsApproval.class::isInstance)
                .map(CapabilityOutcome.NeedsApproval.class::cast)
                .findFirst();

        if (gate.isPresent()) {
            LoopExecutionState blocked = state.withPendingBlock(
                    new LoopExecutionState.PendingBlock(gate.get().gate(), gate.get().gateRef()));
            return new LoopStep(
                    blocked.withPhase(Phase.AWAITING_CHECKPOINT),
                    new LoopDecision.Checkpoint(CheckpointKind.BEFORE_BLOCK));
        }

        List<ContentBlock.ToolResult> results = new ArrayList<>();
        List<io.jclaw.contracts.turn.TurnRef.LoopResultRef> refs = new ArrayList<>();
        for (Observation.CallOutcome outcome : completed.outcomes()) {
            results.add(new ContentBlock.ToolResult(
                    outcome.callId(),
                    outcome.outcome().modelFacingText(),
                    outcome.outcome().isError()));
            if (outcome.outcome() instanceof CapabilityOutcome.Ok ok) {
                refs.add(ok.resultRef());
            }
        }

        LoopExecutionState advanced = state
                .withMessageAppended(ChatMessage.toolResults(results))
                .withResultRefs(refs)
                .withIterationAdvanced();

        Budget.Exhaustion exhausted = advanced.budget().exhaustion(now);
        if (exhausted != Budget.Exhaustion.NONE) {
            return finish(advanced, LoopExit.Failed.of(FailureKind.BUDGET_EXHAUSTED, exhausted.reason()));
        }
        return checkpointThenModel(advanced);
    }

    private static LoopStep onReplyPersisted(LoopExecutionState state, Observation observation) {
        if (!(observation instanceof Observation.ReplyPersisted persisted)) {
            return protocolViolation(state, "expected ReplyPersisted");
        }
        LoopExecutionState withRef = state.withAssistantRef(persisted.ref());
        return finish(withRef, new LoopExit.Completed(withRef.assistantRefs(), withRef.resultRefs()));
    }

    // --- Helpers ---

    /** Every model call is preceded by a checkpoint, so an expired lease can always resume. */
    private static LoopStep checkpointThenModel(LoopExecutionState state) {
        return new LoopStep(
                state.withPhase(Phase.AWAITING_CHECKPOINT),
                new LoopDecision.Checkpoint(CheckpointKind.BEFORE_MODEL));
    }

    private static LoopStep finish(LoopExecutionState state, LoopExit exit) {
        return new LoopStep(state.withPhase(Phase.DONE), new LoopDecision.Finish(exit));
    }

    private static LoopStep protocolViolation(LoopExecutionState state, String detail) {
        return finish(state, LoopExit.Failed.of(FailureKind.DRIVER_PROTOCOL_VIOLATION, detail));
    }

    /**
     * The model's view of the conversation.
     *
     * <p>The state keeps every message; the request carries what the context policy admits. Doing
     * this here, on every call, is what bounds a long tool-heavy run as well as a long thread:
     * the seed is compacted at admission, but tool results accumulate inside a run too.
     */
    private static ModelRequest buildRequest(LoopExecutionState state, LoopPolicy policy) {
        return new ModelRequest(
                policy.model(),
                policy.systemPrompt(),
                ContextCompaction.compact(state.messages(), policy.context()).messages(),
                policy.tools(),
                policy.maxOutputTokens(),
                Optional.empty());
    }
}
