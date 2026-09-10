// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.loop;

import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.model.ModelExchange.StopReason;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.TurnRef.LoopMessageRef;
import io.jclaw.domain.budget.Budget;
import io.jclaw.domain.loop.LoopExecutionState.Phase;
import io.jclaw.domain.loop.TurnMachine.LoopStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveFamilyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final LoopPolicy POLICY = LoopPolicy.of("m", "be helpful", List.of()).withFamily("reflective");
    private static final LoopFamily FAMILY = LoopFamilies.REFLECTIVE;

    private static ModelResponse text(String text) {
        return new ModelResponse(ChatMessage.assistant(text), StopReason.END_TURN, Usage.of(10, 5), "m");
    }

    /** Drives Start and the checkpoint so the state is awaiting its first model reply. */
    private static LoopExecutionState awaitingModel() {
        LoopExecutionState state = LoopExecutionState.start(List.of(ChatMessage.user("hi")), Budget.interactive(T0));
        LoopStep started = FAMILY.step(state, new Observation.Start(), POLICY, T0);
        LoopStep called = FAMILY.step(started.state(), new Observation.Checkpointed(
                new io.jclaw.contracts.turn.TurnRef.LoopCheckpointStateRef("ckpt_1"),
                io.jclaw.contracts.loop.CheckpointKind.BEFORE_MODEL), POLICY, T0);
        assertTrue(called.decision() instanceof LoopDecision.CallModel);
        assertEquals(Phase.AWAITING_MODEL, called.state().phase());
        return called.state();
    }

    @Test
    @DisplayName("a plain reply is reviewed once, without tools or streaming, and the revision is persisted")
    void reviewsThenPersists() {
        LoopStep review = FAMILY.step(awaitingModel(), new Observation.ModelReplied(text("draft")), POLICY, T0);

        assertEquals(Phase.AWAITING_REFLECTION, review.state().phase());
        LoopDecision.CallModel call = (LoopDecision.CallModel) review.decision();
        assertFalse(call.userFacing(), "the review is not the agent speaking");
        assertTrue(call.request().tools().isEmpty());
        assertEquals("draft", call.request().messages().get(1).displayText(), "the draft is in the conversation");
        assertTrue(call.request().messages().get(2).displayText().contains("Review your previous reply"));

        LoopStep persist = FAMILY.step(review.state(), new Observation.ModelReplied(text("final")), POLICY, T0);
        LoopDecision.PersistReply decision = (LoopDecision.PersistReply) persist.decision();
        assertEquals("final", decision.message().displayText());
        assertEquals(Phase.AWAITING_REPLY_PERSIST, persist.state().phase());
        assertEquals(List.of("hi", "final"), persist.state().messages().stream().map(ChatMessage::displayText).toList(),
                "the draft is replaced, not kept beside the revision");
        assertEquals(30, persist.state().budget().spent().total(), "both calls are charged");

        LoopStep done = FAMILY.step(persist.state(), new Observation.ReplyPersisted(new LoopMessageRef("msg_1")), POLICY, T0);
        assertTrue(done.decision() instanceof LoopDecision.Finish, "the canonical machine finishes the turn");
    }

    @Test
    @DisplayName("a failed review, or an empty revision, keeps the draft")
    void fallsBackToDraft() {
        LoopStep review = FAMILY.step(awaitingModel(), new Observation.ModelReplied(text("draft")), POLICY, T0);

        LoopStep failed = FAMILY.step(review.state(),
                new Observation.ModelFailed(FailureKind.PROVIDER_ERROR, true), POLICY, T0);
        assertEquals("draft", ((LoopDecision.PersistReply) failed.decision()).message().displayText());

        LoopStep empty = FAMILY.step(review.state(), new Observation.ModelReplied(text("   ")), POLICY, T0);
        assertEquals("draft", ((LoopDecision.PersistReply) empty.decision()).message().displayText());
    }

    @Test
    @DisplayName("tool rounds pass through untouched, and the canonical family never reflects")
    void toolsAndCanonical() {
        ChatMessage toolUse = new ChatMessage(ChatMessage.Role.ASSISTANT,
                List.of(new ContentBlock.ToolUse("c1", "builtin.echo", Map.of())));
        ModelResponse response = new ModelResponse(toolUse, StopReason.TOOL_USE, Usage.of(1, 1), "m");
        LoopStep step = FAMILY.step(awaitingModel(), new Observation.ModelReplied(response), POLICY, T0);
        assertTrue(step.decision() instanceof LoopDecision.InvokeCapabilities);

        LoopStep canonical = LoopFamilies.CANONICAL.step(awaitingModel(), new Observation.ModelReplied(text("draft")), POLICY, T0);
        assertTrue(canonical.decision() instanceof LoopDecision.PersistReply);

        assertEquals(Optional.of(LoopFamilies.REFLECTIVE), LoopFamilies.byId("Reflective"));
        assertEquals(Optional.of(LoopFamilies.CANONICAL), LoopFamilies.byId(""));
        assertTrue(LoopFamilies.byId("planner").isEmpty());
    }
}
