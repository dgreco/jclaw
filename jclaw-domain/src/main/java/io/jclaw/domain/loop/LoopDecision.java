// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.loop;

import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.LoopExit;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;

import java.util.List;
import java.util.Objects;

/**
 * What the pure machine wants done next.
 *
 * <p>A decision is a <em>request</em>, described in data. The machine performs no I/O and holds no
 * ports; the effect interpreter in {@code jclaw-loop} is the single place that turns these into
 * real calls. That split is what makes the loop testable without a network, a filesystem, or a
 * clock — you feed it observations and assert on decisions.
 *
 * <p>It is also what makes the security story tractable. Every effect the agent can cause is one
 * of these five constructors, so the set of things a loop can ask for is closed and reviewable.
 */
public sealed interface LoopDecision {

    /**
     * Send a request to the model. Preceded by a {@link CheckpointKind#BEFORE_MODEL} checkpoint.
     *
     * @param userFacing whether the reply is part of the conversation the user is watching. A
     *                   context-summary call is not: its output must not be streamed to a
     *                   terminal as if the agent were speaking.
     */
    record CallModel(ModelRequest request, boolean userFacing) implements LoopDecision {
        public CallModel {
            Objects.requireNonNull(request, "request");
        }

        public CallModel(ModelRequest request) {
            this(request, true);
        }
    }

    /**
     * Dispatch the model's tool calls through the capability host.
     *
     * <p>Carries the calls rather than a reference to them so the interpreter needs no access to
     * loop state, and so a replay with the same decision does exactly the same work.
     */
    record InvokeCapabilities(List<ContentBlock.ToolUse> calls) implements LoopDecision {
        public InvokeCapabilities {
            calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("InvokeCapabilities requires at least one call");
            }
        }
    }

    /**
     * Write an assistant message to the transcript and mint its ref.
     *
     * <p>The machine cannot mint refs itself — that is the point. It asks, and the host answers
     * with evidence it can later hand back in a {@link LoopExit}.
     */
    record PersistReply(ChatMessage message, boolean draft) implements LoopDecision {
        public PersistReply {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Persist a checkpoint of the current state. */
    record Checkpoint(CheckpointKind kind) implements LoopDecision {
        public Checkpoint {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Stop. The exit is a claim the runner will validate against host-minted evidence. */
    record Finish(LoopExit exit) implements LoopDecision {
        public Finish {
            Objects.requireNonNull(exit, "exit");
        }
    }

    /** Whether this decision performs an externally visible side effect. */
    default boolean hasSideEffect() {
        return switch (this) {
            case CallModel ignored -> true;
            case InvokeCapabilities ignored -> true;
            case PersistReply ignored -> true;
            case Checkpoint ignored -> false;
            case Finish ignored -> false;
        };
    }
}
