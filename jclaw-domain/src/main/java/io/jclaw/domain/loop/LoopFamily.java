package io.jclaw.domain.loop;

import java.time.Instant;

/**
 * A loop strategy: the pure function that turns an observation into the next decision.
 *
 * <p>The canonical machine is one family. Another family composes the same phases differently,
 * such as asking the model to review a draft before it is persisted, while keeping the same
 * state, decisions, observations, checkpoints, and exits, so the interpreter, the stores, and
 * recovery are indifferent to which family drove a run. What a family may not do is invent a
 * new kind of effect: {@link LoopDecision} is sealed and the interpreter is the only executor.
 */
public interface LoopFamily {

    /** A stable id, as written in configuration. */
    String id();

    TurnMachine.LoopStep step(LoopExecutionState state, Observation observation, LoopPolicy policy, Instant now);
}
