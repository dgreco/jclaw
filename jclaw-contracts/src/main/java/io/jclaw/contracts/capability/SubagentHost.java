package io.jclaw.contracts.capability;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.turn.TurnScope;

import java.util.Objects;

/**
 * Spawns a child run and waits for its reply.
 *
 * <p>A port rather than a direct call because the tool layer sits below the composition root: the
 * capability that spawns a subagent lives in {@code jclaw-tools}, and the runtime that executes one
 * lives in {@code jclaw-app}. Inverting the dependency here keeps the layer ladder intact.
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
}
