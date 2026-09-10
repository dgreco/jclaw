// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.loop;

import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.LoopExit;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.domain.loop.LoopExecutionState.Phase;
import io.jclaw.domain.loop.TurnMachine.LoopStep;
import io.jclaw.domain.prompt.ContextCompaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** The loop families jclaw ships. */
public final class LoopFamilies {

    /** The canonical machine, unchanged. */
    public static final LoopFamily CANONICAL = new LoopFamily() {
        @Override
        public String id() {
            return "canonical";
        }

        @Override
        public LoopStep step(LoopExecutionState state, Observation observation, LoopPolicy policy, Instant now) {
            return TurnMachine.step(state, observation, policy, now);
        }
    };

    /** The instruction {@link #REFLECTIVE} reviews with; a configured family supplies its own. */
    static final String REVIEW_INSTRUCTION =
            "Review your previous reply for correctness, completeness, and clarity against the "
                    + "conversation so far. Then output only the final reply you stand behind, "
                    + "revised if needed, with no commentary about the review.";

    /**
     * Reflect before replying: when the canonical machine would persist a final reply, this family
     * first asks the model, without tools and without streaming, to review that draft and return
     * the reply it stands behind, then persists the revision.
     *
     * <p>Everything else is the canonical machine: tool rounds, gates, checkpoints, failures. The
     * review call is charged to the budget like any other, is skipped when the budget is already
     * exhausted, and falls back to the draft when it fails, so reflection can cost a call but
     * never a turn.
     */
    public static final LoopFamily REFLECTIVE = reviewing("reflective", REVIEW_INSTRUCTION);

    private LoopFamilies() {
    }

    /**
     * A reviewing family with the operator's own review instruction.
     *
     * <p>{@link #REFLECTIVE} is this with the default instruction. What makes it worth
     * configuring is the case that actually recurs: a team wants the review to check something
     * their work needs checked — that a migration is reversible, that no customer name appears in
     * the answer, that the reply cites a file it read. Writing a Java class for that was more
     * ceremony than the change deserved.
     *
     * <p>The number of passes is <em>not</em> configurable, and that is a choice rather than an
     * oversight. A second pass needs a counter in {@link LoopExecutionState}, which means a record
     * component and a checkpoint codec change — friction this codebase keeps on purpose — to buy
     * something speculative: each pass is another model call on the same budget, and there is no
     * evidence a third look improves an answer more than it costs. One pass, or a family in Java.
     *
     * @param id          the name the operator configured, used in logs and the run record
     * @param instruction what to ask the model when reviewing its draft
     */
    public static LoopFamily reviewing(String id, String instruction) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instruction, "instruction");
        if (id.isBlank()) {
            throw new IllegalArgumentException("a family needs an id");
        }
        if (instruction.isBlank()) {
            throw new IllegalArgumentException("a reviewing family needs a review instruction");
        }
        return new LoopFamily() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public LoopStep step(LoopExecutionState state, Observation observation, LoopPolicy policy, Instant now) {
                if (state.phase() == Phase.AWAITING_REFLECTION) {
                    return onReflection(state, observation);
                }
                LoopStep canonical = TurnMachine.step(state, observation, policy, now);
                if (canonical.decision() instanceof LoopDecision.PersistReply persist
                        && !persist.draft()
                        && state.phase() == Phase.AWAITING_MODEL
                        && !canonical.state().budget().isExhausted(now)) {
                    LoopExecutionState reflecting = canonical.state().withPhase(Phase.AWAITING_REFLECTION);
                    return new LoopStep(reflecting,
                            new LoopDecision.CallModel(reviewRequest(reflecting, policy, instruction), false));
                }
                return canonical;
            }
        };
    }

    /** The family for a configured id, or empty when the id names none. */
    public static Optional<LoopFamily> byId(String id) {
        return switch (id == null ? "" : id.trim().toLowerCase(Locale.ROOT)) {
            case "canonical", "" -> Optional.of(CANONICAL);
            case "reflective" -> Optional.of(REFLECTIVE);
            default -> Optional.empty();
        };
    }

    private static ModelRequest reviewRequest(
            LoopExecutionState state, LoopPolicy policy, String instruction) {
        List<ChatMessage> messages = new ArrayList<>(
                ContextCompaction.compact(state.messages(), policy.context()).messages());
        messages.add(ChatMessage.user(instruction));
        return new ModelRequest(policy.model(), policy.systemPrompt(), messages, List.of(),
                policy.maxOutputTokens(), Optional.empty());
    }

    private static LoopStep onReflection(LoopExecutionState state, Observation observation) {
        ChatMessage draft = state.pendingReply().orElseThrow(
                () -> new IllegalStateException("reflection without a draft"));
        return switch (observation) {
            case Observation.ModelReplied replied -> {
                LoopExecutionState charged = state
                        .withBudget(state.budget().charge(replied.response().usage()))
                        .withModelSuccess();
                String revised = replied.response().text().strip();
                ChatMessage reply = revised.isEmpty() ? draft : ChatMessage.assistant(revised);
                yield persist(charged, replaceLastAssistant(charged.messages(), reply), reply);
            }
            // A failed or gated review is not worth a turn: the draft stands.
            case Observation.ModelFailed ignored -> persist(state, state.messages(), draft);
            case Observation.AuthRequired ignored -> persist(state, state.messages(), draft);
            case Observation.CancelRequested ignored -> new LoopStep(
                    state.withPhase(Phase.DONE), new LoopDecision.Finish(LoopExit.Cancelled.withoutCheckpoint()));
            default -> new LoopStep(state.withPhase(Phase.DONE), new LoopDecision.Finish(
                    LoopExit.Failed.of(FailureKind.DRIVER_PROTOCOL_VIOLATION, "expected a review reply")));
        };
    }

    private static LoopStep persist(LoopExecutionState state, List<ChatMessage> messages, ChatMessage reply) {
        return new LoopStep(
                state.withMessages(messages).withPendingReply(reply).withPhase(Phase.AWAITING_REPLY_PERSIST),
                new LoopDecision.PersistReply(reply, false));
    }

    private static List<ChatMessage> replaceLastAssistant(List<ChatMessage> messages, ChatMessage reply) {
        List<ChatMessage> out = new ArrayList<>(messages);
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i).role() == ChatMessage.Role.ASSISTANT) {
                out.set(i, reply);
                return out;
            }
        }
        out.add(reply);
        return out;
    }
}
