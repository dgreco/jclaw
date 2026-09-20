// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.capability;

import io.jclaw.ports.Result;
import io.jclaw.ports.turn.TurnRunId;
import io.jclaw.ports.turn.TurnScope;

import java.util.Objects;

/**
 * Spawns a child run and waits for its reply.
 *
 * <p>A port rather than a direct call because the tool layer sits below the composition root: the
 * capability that spawns a subagent lives in {@code jclaw-adapter-out-capability}, and the runtime that executes one
 * lives in {@code jclaw-bootstrap}. Inverting the dependency here keeps the layer ladder intact.
 *
 * <p>Following IronClaw, a subagent is a <b>child run on the same machinery</b>, not a second
 * private loop engine. It goes through the same turn machine, the same effect interpreter, and
 * critically the same {@link CapabilityHost} — so a subagent cannot reach anything its parent could
 * not, and every effect it causes is authorized, gated, and audited identically. A separate
 * execution path for subagents would be a second place for authority to leak.
 */
public interface SubagentHost {

    /** Maximum nesting depth. A subagent that can spawn subagents can recurse without bound. */
    int MAX_DEPTH = 3;

    /**
     * What a child run produced.
     *
     * @param reply     the child's final text, already validated against durable evidence
     * @param succeeded whether the child completed rather than failing or parking
     */
    record SubagentResult(String reply, boolean succeeded, long tokensSpent) {
        public SubagentResult {
            Objects.requireNonNull(reply, "reply");
        }
    }

    /**
     * Runs a child turn to completion.
     *
     * @param parentScope scope of the spawning run; the child inherits it, so it cannot widen its
     *                    own isolation boundary
     * @param depth       current nesting depth; implementations must refuse at {@link #MAX_DEPTH}
     * @return the child's result, or a stable denial/failure category
     */
    Result<SubagentResult, String> spawn(
            TurnScope parentScope, String description, String prompt, int depth);

    /** Where an asynchronous child stands. */
    sealed interface Progress {

        /** Queued or executing; the parent should park on a process gate and ask again later. */
        record Running(TurnRunId child) implements Progress {
            public Running {
                Objects.requireNonNull(child, "child");
            }
        }

        /** Done, one way or the other. */
        record Finished(TurnRunId child, SubagentResult result) implements Progress {
            public Finished {
                Objects.requireNonNull(child, "child");
                Objects.requireNonNull(result, "result");
            }
        }
    }

    /**
     * Starts a child turn if none exists for this task, or reports how the existing one is doing.
     *
     * <p>Idempotent by design: the parent parks and later re-dispatches the same invocation, and
     * the second call must find the child the first one started rather than start another. The
     * child is identified by the parent thread and the task, never by anything the model supplies
     * separately.
     */
    Result<Progress, String> spawnAsync(TurnScope parentScope, String description, String prompt);
}
