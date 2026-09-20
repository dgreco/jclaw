// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.loop;

import io.jclaw.ports.capability.CapabilityOutcome;
import io.jclaw.ports.loop.CheckpointKind;
import io.jclaw.ports.loop.FailureKind;
import io.jclaw.ports.loop.GateKind;
import io.jclaw.ports.loop.LoopExit;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ContentBlock;
import io.jclaw.ports.model.ModelExchange.ModelResponse;
import io.jclaw.ports.model.ModelExchange.StopReason;
import io.jclaw.ports.model.ModelExchange.Usage;
import io.jclaw.ports.turn.CheckpointId;
import io.jclaw.ports.turn.GateId;
import io.jclaw.ports.turn.MessageId;
import io.jclaw.ports.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.ports.turn.TurnRef.LoopMessageRef;
import io.jclaw.ports.turn.TurnRef.LoopResultRef;
import io.jclaw.domain.budget.Budget;
import io.jclaw.domain.loop.LoopExecutionState.Phase;
import io.jclaw.domain.loop.TurnMachine.LoopStep;
import io.jclaw.domain.prompt.ContextPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine is a pure function, so these tests need no mocks, no clock, and no I/O — just
 * values in and values out. That is the whole argument for the design.
 */
class TurnMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final LoopPolicy POLICY = LoopPolicy.of("test-model", "be helpful", List.of());

    private static LoopExecutionState fresh() {
        return LoopExecutionState.start(
                List.of(ChatMessage.user("hello")), Budget.interactive(T0));
    }

    private static ModelResponse textReply(String text) {
        return new ModelResponse(
                ChatMessage.assistant(text), StopReason.END_TURN, Usage.of(10, 5), "test-model");
    }

    private static ModelResponse toolReply(String callId, String tool) {
        ChatMessage message = new ChatMessage(
                ChatMessage.Role.ASSISTANT,
                List.of(new ContentBlock.ToolUse(callId, tool, Map.of("path", "README.md"))));
        return new ModelResponse(message, StopReason.TOOL_USE, Usage.of(10, 5), "test-model");
    }

    private static LoopStep step(LoopExecutionState state, Observation observation) {
        return TurnMachine.step(state, observation, POLICY, T0);
    }

    @Nested
    @DisplayName("context summarisation")
    class Summarisation {

        private LoopExecutionState longState() {
            List<ChatMessage> history = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                history.add(ChatMessage.user("question " + i));
                history.add(ChatMessage.assistant("answer " + i));
            }
            history.add(ChatMessage.user("latest"));
            return LoopExecutionState.start(history, Budget.interactive(T0));
        }

        private final LoopPolicy summarising = POLICY.withContext(
                new ContextPolicy(3, 100_000, true, 256));

        @Test
        @DisplayName("a dropped span is summarised first, then the real call carries the summary")
        void summarisesThenCalls() {
            LoopStep started = TurnMachine.step(longState(), new Observation.Start(), summarising, T0);
            LoopStep afterCheckpoint = TurnMachine.step(
                    started.state(), checkpointed(CheckpointKind.BEFORE_MODEL), summarising, T0);

            LoopDecision.CallModel summary =
                    assertInstanceOf(LoopDecision.CallModel.class, afterCheckpoint.decision());
            assertFalse(summary.userFacing(), "a summary is not the agent speaking");
            assertTrue(summary.request().tools().isEmpty());
            assertTrue(summary.request().messages().get(0).displayText().contains("question 0"));
            assertEquals(Phase.AWAITING_SUMMARY, afterCheckpoint.state().phase());

            LoopStep afterSummary = TurnMachine.step(afterCheckpoint.state(),
                    new Observation.ModelReplied(textReply("Earlier: six questions were answered.")),
                    summarising, T0);
            LoopDecision.CallModel real =
                    assertInstanceOf(LoopDecision.CallModel.class, afterSummary.decision());
            assertTrue(real.userFacing());
            String first = real.request().messages().get(0).displayText();
            assertTrue(first.contains("Summary of the omitted messages: Earlier: six questions were answered."));
            assertEquals("latest", real.request().messages().get(real.request().messages().size() - 1).displayText());
            assertEquals(Phase.AWAITING_MODEL, afterSummary.state().phase());
            assertTrue(afterSummary.state().messages().size() < 13, "the state itself shrank");
            assertEquals(15, afterSummary.state().budget().spent().total(), "the summary call is charged");
        }

        @Test
        @DisplayName("a failed summary falls back to truncation without failing the run")
        void failedSummaryFallsBack() {
            LoopStep started = TurnMachine.step(longState(), new Observation.Start(), summarising, T0);
            LoopStep afterCheckpoint = TurnMachine.step(
                    started.state(), checkpointed(CheckpointKind.BEFORE_MODEL), summarising, T0);

            LoopStep afterFailure = TurnMachine.step(afterCheckpoint.state(),
                    new Observation.ModelFailed(FailureKind.PROVIDER_ERROR, true), summarising, T0);

            LoopDecision.CallModel real =
                    assertInstanceOf(LoopDecision.CallModel.class, afterFailure.decision());
            assertTrue(real.userFacing());
            assertTrue(real.request().messages().get(0).displayText().startsWith("[Context notice:"));
            assertFalse(real.request().messages().get(0).displayText().contains("Summary of"));
        }

        @Test
        @DisplayName("nothing to drop means no summary call")
        void noSummaryWhenNothingDropped() {
            LoopStep started = TurnMachine.step(fresh(), new Observation.Start(), summarising, T0);
            LoopStep afterCheckpoint = TurnMachine.step(
                    started.state(), checkpointed(CheckpointKind.BEFORE_MODEL), summarising, T0);
            assertTrue(assertInstanceOf(LoopDecision.CallModel.class, afterCheckpoint.decision()).userFacing());
            assertEquals(Phase.AWAITING_MODEL, afterCheckpoint.state().phase());
        }
    }

    @Nested
    @DisplayName("auth gates")
    class AuthGates {

        @Test
        @DisplayName("a provider refusing for want of credentials parks the run, and resume retries the model")
        void authRequiredParksAndResumeRetries() {
            LoopExecutionState awaitingModel = advanceToAwaitingModel(fresh());

            LoopStep parked = step(awaitingModel,
                    new Observation.AuthRequired(new io.jclaw.ports.turn.TurnRef.LoopGateRef("gate_auth")));
            LoopDecision.Checkpoint checkpoint =
                    assertInstanceOf(LoopDecision.Checkpoint.class, parked.decision());
            assertEquals(CheckpointKind.BEFORE_BLOCK, checkpoint.kind(),
                    "nothing was appended, so the block checkpoint is replay-safe");

            LoopStep blocked = step(parked.state(), checkpointed(CheckpointKind.BEFORE_BLOCK));
            LoopExit.Blocked exit = assertInstanceOf(LoopExit.Blocked.class,
                    assertInstanceOf(LoopDecision.Finish.class, blocked.decision()).exit());
            assertEquals(GateKind.AUTH, exit.gate());
            assertEquals("gate_auth", exit.gateRef().value());

            // Resume: no outstanding tool calls, so the machine goes straight back to the model.
            LoopStep resumed = step(
                    blocked.state().withPhase(Phase.RESUMING), new Observation.Resumed());
            assertEquals(CheckpointKind.BEFORE_MODEL,
                    assertInstanceOf(LoopDecision.Checkpoint.class, resumed.decision()).kind());
            assertTrue(resumed.state().pendingBlock().isEmpty(), "the gate is cleared on resume");
        }
    }

    @Nested
    @DisplayName("context policy")
    class ContextPolicyView {

        @Test
        @DisplayName("the request carries the compacted view while the state keeps everything")
        void requestIsCompactedStateIsNot() {
            List<ChatMessage> longHistory = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                longHistory.add(ChatMessage.user("question " + i));
                longHistory.add(ChatMessage.assistant("answer " + i));
            }
            longHistory.add(ChatMessage.user("latest"));
            LoopExecutionState state = LoopExecutionState.start(longHistory, Budget.interactive(T0));
            LoopPolicy narrow = POLICY.withContext(new ContextPolicy(3, 100_000));

            LoopStep started = TurnMachine.step(state, new Observation.Start(), narrow, T0);
            LoopStep called = TurnMachine.step(
                    started.state(), checkpointed(CheckpointKind.BEFORE_MODEL), narrow, T0);

            LoopDecision.CallModel call = assertInstanceOf(LoopDecision.CallModel.class, called.decision());
            assertEquals(3, call.request().messages().size(), "the model sees the policy's window");
            assertTrue(call.request().messages().get(0).displayText().contains("Context notice"),
                    "the model is told history was omitted");
            assertEquals("latest", call.request().messages().get(2).displayText());
            assertEquals(longHistory, called.state().messages(),
                    "the state is the full truth; only the request view is bounded");
        }
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("a plain reply runs checkpoint -> model -> persist -> complete")
        void plainReplyCompletes() {
            // START: the machine checkpoints before ever calling the model, so a lost lease
            // here can always be resumed without repeating an effect.
            LoopStep started = step(fresh(), new Observation.Start());
            LoopDecision.Checkpoint checkpoint =
                    assertInstanceOf(LoopDecision.Checkpoint.class, started.decision());
            assertEquals(CheckpointKind.BEFORE_MODEL, checkpoint.kind());
            assertEquals(Phase.AWAITING_CHECKPOINT, started.state().phase());

            // The checkpoint lands; now the model call is issued.
            LoopStep called = step(started.state(), checkpointed(CheckpointKind.BEFORE_MODEL));
            LoopDecision.CallModel call = assertInstanceOf(LoopDecision.CallModel.class, called.decision());
            assertEquals("test-model", call.request().model());
            assertEquals("be helpful", call.request().system());
            assertEquals(Phase.AWAITING_MODEL, called.state().phase());

            // Model replies with prose: the machine asks the host to persist it.
            LoopStep replied = step(called.state(), new Observation.ModelReplied(textReply("hi there")));
            LoopDecision.PersistReply persist =
                    assertInstanceOf(LoopDecision.PersistReply.class, replied.decision());
            assertEquals("hi there", persist.message().displayText());
            assertEquals(15, replied.state().budget().spent().total(), "usage is charged on reply");

            // Only once the host mints a ref can the machine claim completion.
            LoopStep done = step(replied.state(), new Observation.ReplyPersisted(messageRef("m1")));
            LoopDecision.Finish finish = assertInstanceOf(LoopDecision.Finish.class, done.decision());
            LoopExit.Completed completed = assertInstanceOf(LoopExit.Completed.class, finish.exit());
            assertEquals(List.of(messageRef("m1")), completed.replyRefs());
            assertEquals(Phase.DONE, done.state().phase());
        }

        @Test
        @DisplayName("a tool call loops back through the model")
        void toolCallLoops() {
            LoopExecutionState awaitingModel = advanceToAwaitingModel(fresh());

            LoopStep replied = step(awaitingModel, new Observation.ModelReplied(toolReply("c1", "builtin.read_file")));
            LoopDecision.InvokeCapabilities invoke =
                    assertInstanceOf(LoopDecision.InvokeCapabilities.class, replied.decision());
            assertEquals(1, invoke.calls().size());
            assertEquals("builtin.read_file", invoke.calls().get(0).name());

            // Results come back; the machine records them and checkpoints before the next model call.
            LoopStep afterTools = step(replied.state(), new Observation.CapabilitiesCompleted(List.of(
                    new Observation.CallOutcome("c1",
                            new CapabilityOutcome.Ok(new LoopResultRef("r1"), "file contents", false)))));

            LoopDecision.Checkpoint checkpoint =
                    assertInstanceOf(LoopDecision.Checkpoint.class, afterTools.decision());
            assertEquals(CheckpointKind.BEFORE_MODEL, checkpoint.kind());
            assertEquals(1, afterTools.state().iteration(), "a completed tool round advances the iteration");
            assertEquals(List.of(new LoopResultRef("r1")), afterTools.state().resultRefs());

            // The tool result was appended for the model to read.
            ChatMessage last = afterTools.state().messages().get(afterTools.state().messages().size() - 1);
            assertEquals(ChatMessage.Role.TOOL, last.role());
        }
    }

    @Nested
    @DisplayName("gates")
    class Gates {

        @Test
        @DisplayName("an approval gate checkpoints then blocks, carrying both refs")
        void approvalGateBlocks() {
            LoopExecutionState awaitingCaps = advanceToAwaitingCapabilities();

            LoopStep gated = step(awaitingCaps, new Observation.CapabilitiesCompleted(List.of(
                    new Observation.CallOutcome("c1", new CapabilityOutcome.NeedsApproval(
                            GateKind.APPROVAL, gateRef("g1"), "run rm -rf?")))));

            LoopDecision.Checkpoint checkpoint =
                    assertInstanceOf(LoopDecision.Checkpoint.class, gated.decision());
            assertEquals(CheckpointKind.BEFORE_BLOCK, checkpoint.kind());

            LoopStep blocked = step(gated.state(), checkpointed(CheckpointKind.BEFORE_BLOCK));
            LoopDecision.Finish finish = assertInstanceOf(LoopDecision.Finish.class, blocked.decision());
            LoopExit.Blocked exit = assertInstanceOf(LoopExit.Blocked.class, finish.exit());

            assertEquals(GateKind.APPROVAL, exit.gate());
            assertEquals(gateRef("g1"), exit.gateRef());
            assertEquals(checkpointRef(), exit.checkpointRef());
            assertTrue(!exit.claimedStatus().isTerminal(), "a blocked run keeps the active lock");
        }

        @Test
        @DisplayName("a BEFORE_BLOCK checkpoint with no pending gate is a protocol violation")
        void blockWithoutGateIsViolation() {
            // Reaching AWAITING_CHECKPOINT without a gate, then claiming BEFORE_BLOCK, would let a
            // buggy interpreter park a run with nothing for a human to resolve.
            LoopStep started = step(fresh(), new Observation.Start());
            LoopStep bogus = step(started.state(), checkpointed(CheckpointKind.BEFORE_BLOCK));

            assertEquals(FailureKind.DRIVER_PROTOCOL_VIOLATION, failureOf(bogus));
        }
    }

    @Nested
    @DisplayName("budgets and failures")
    class BudgetsAndFailures {

        @Test
        @DisplayName("an exhausted budget fails before any model call")
        void exhaustedBudgetFailsClosed() {
            Budget spent = Budget.of(100, 50, Duration.ofMinutes(10), T0).charge(Usage.of(60, 60));
            LoopExecutionState state =
                    LoopExecutionState.start(List.of(ChatMessage.user("hi")), spent);

            LoopStep result = step(state, new Observation.Start());

            assertEquals(FailureKind.BUDGET_EXHAUSTED, failureOf(result));
            assertEquals(Phase.DONE, result.state().phase());
        }

        @Test
        @DisplayName("wall-clock exhaustion uses the supplied clock, not the system clock")
        void wallClockUsesSuppliedInstant() {
            Budget budget = Budget.of(0, 0, Duration.ofMinutes(5), T0);
            LoopExecutionState state = LoopExecutionState.start(List.of(ChatMessage.user("hi")), budget);

            LoopStep inTime = TurnMachine.step(state, new Observation.Start(), POLICY, T0.plusSeconds(60));
            assertInstanceOf(LoopDecision.Checkpoint.class, inTime.decision());

            LoopStep tooLate = TurnMachine.step(state, new Observation.Start(), POLICY, T0.plusSeconds(600));
            assertEquals(FailureKind.BUDGET_EXHAUSTED, failureOf(tooLate));
        }

        @Test
        @DisplayName("a retryable model failure retries, then gives up at the policy limit")
        void retriesThenGivesUp() {
            LoopExecutionState state = advanceToAwaitingModel(fresh());

            // POLICY allows 2 consecutive failures, so the first two retry. A retry re-enters
            // AWAITING_CHECKPOINT, so the way back to AWAITING_MODEL is to deliver the
            // checkpoint — not to replay Start, which would itself be a protocol violation.
            for (int attempt = 1; attempt <= 2; attempt++) {
                LoopStep retry = step(state, new Observation.ModelFailed(FailureKind.PROVIDER_ERROR, true));
                assertInstanceOf(LoopDecision.Checkpoint.class, retry.decision(),
                        "attempt " + attempt + " should retry");
                state = step(retry.state(), checkpointed(CheckpointKind.BEFORE_MODEL)).state();
                assertEquals(Phase.AWAITING_MODEL, state.phase());
            }

            LoopStep exhausted = step(state, new Observation.ModelFailed(FailureKind.PROVIDER_ERROR, true));
            assertEquals(FailureKind.PROVIDER_ERROR, failureOf(exhausted));
        }

        @Test
        @DisplayName("a non-retryable model failure gives up immediately")
        void nonRetryableFailsAtOnce() {
            LoopExecutionState state = advanceToAwaitingModel(fresh());

            LoopStep result = step(state, new Observation.ModelFailed(FailureKind.PROVIDER_UNAVAILABLE, false));

            assertEquals(FailureKind.PROVIDER_UNAVAILABLE, failureOf(result));
        }
    }

    @Nested
    @DisplayName("protocol discipline")
    class ProtocolDiscipline {

        @Test
        @DisplayName("cancellation is honoured from any phase")
        void cancellationFromAnyPhase() {
            List<LoopExecutionState> phases = List.of(
                    fresh(),
                    advanceToAwaitingModel(fresh()),
                    advanceToAwaitingCapabilities());

            for (LoopExecutionState state : phases) {
                LoopStep result = step(state, new Observation.CancelRequested());
                LoopDecision.Finish finish = assertInstanceOf(LoopDecision.Finish.class, result.decision());
                assertInstanceOf(LoopExit.Cancelled.class, finish.exit(),
                        "cancel should stop the machine in phase " + state.phase());
            }
        }

        @Test
        @DisplayName("an out-of-order observation is a protocol violation, not a silent no-op")
        void outOfOrderObservationFails() {
            // The machine is waiting for a checkpoint; a model reply cannot legitimately arrive.
            LoopStep started = step(fresh(), new Observation.Start());
            LoopStep confused = step(started.state(), new Observation.ModelReplied(textReply("surprise")));

            assertEquals(FailureKind.DRIVER_PROTOCOL_VIOLATION, failureOf(confused));
        }

        @Test
        @DisplayName("observations after termination are rejected")
        void observationAfterDoneRejected() {
            LoopExecutionState done = fresh().withPhase(Phase.DONE);

            LoopStep result = step(done, new Observation.Start());

            assertEquals(FailureKind.DRIVER_PROTOCOL_VIOLATION, failureOf(result));
        }
    }

    @Test
    @DisplayName("replaying the same observations reproduces the same decisions")
    void deterministicReplay() {
        List<Observation> script = List.of(
                new Observation.Start(),
                checkpointed(CheckpointKind.BEFORE_MODEL),
                new Observation.ModelReplied(textReply("deterministic")),
                new Observation.ReplyPersisted(messageRef("m1")));

        List<String> first = run(script);
        List<String> second = run(script);

        assertEquals(first, second, "same inputs must produce the same decision sequence");
        assertEquals(
                List.of("Checkpoint", "CallModel", "PersistReply", "Finish"),
                first,
                "and that sequence is the documented pipeline");
    }

    /** Drives the machine through a script, returning the simple name of each decision. */
    private static List<String> run(List<Observation> script) {
        LoopExecutionState state = fresh();
        List<String> decisions = new ArrayList<>();
        for (Observation observation : script) {
            LoopStep next = step(state, observation);
            decisions.add(next.decision().getClass().getSimpleName());
            state = next.state();
        }
        return decisions;
    }

    // --- fixtures ---

    private static LoopExecutionState advanceToAwaitingModel(LoopExecutionState from) {
        LoopStep started = step(from, new Observation.Start());
        return step(started.state(), checkpointed(CheckpointKind.BEFORE_MODEL)).state();
    }

    private static LoopExecutionState advanceToAwaitingCapabilities() {
        LoopExecutionState awaitingModel = advanceToAwaitingModel(fresh());
        return step(awaitingModel, new Observation.ModelReplied(toolReply("c1", "builtin.shell"))).state();
    }

    private static Observation.Checkpointed checkpointed(CheckpointKind kind) {
        return new Observation.Checkpointed(checkpointRef(), kind);
    }

    private static LoopCheckpointStateRef checkpointRef() {
        return LoopCheckpointStateRef.of(new CheckpointId("ckpt_1"));
    }

    private static LoopMessageRef messageRef(String id) {
        return LoopMessageRef.of(new MessageId(id));
    }

    private static io.jclaw.ports.turn.TurnRef.LoopGateRef gateRef(String id) {
        return io.jclaw.ports.turn.TurnRef.LoopGateRef.of(new GateId(id));
    }

    private static FailureKind failureOf(LoopStep step) {
        LoopDecision.Finish finish = assertInstanceOf(LoopDecision.Finish.class, step.decision());
        LoopExit.Failed failed = assertInstanceOf(LoopExit.Failed.class, finish.exit());
        return failed.kind();
    }
}
