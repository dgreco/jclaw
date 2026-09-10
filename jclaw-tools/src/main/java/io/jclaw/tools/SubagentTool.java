// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.tools;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.HandlerError;
import io.jclaw.contracts.capability.SubagentHost;

import java.util.List;
import java.util.Objects;

/**
 * Delegates a self-contained task to a child agent run.
 *
 * <p>Useful when a sub-task would otherwise fill the parent's context with material it does not
 * need afterwards — reading a large file to answer one question, sweeping a directory, checking a
 * claim. The child does the reading; the parent gets the conclusion.
 *
 * <p>Declared {@link EffectClass#PROCESS} rather than something milder, deliberately. A subagent
 * inherits the parent's capability surface, so spawning one is not a read: it is authorizing a
 * whole additional agent run whose individual effects the human will not see prompts for. Under
 * the default policy it therefore asks first, which is the honest position given IronClaw ships
 * with its own {@code spawn_subagent} deny-filtered off entirely.
 *
 * <p>Depth is bounded by {@link SubagentHost#MAX_DEPTH}. An agent that can spawn agents can recurse
 * until something runs out.
 */
public final class SubagentTool implements CapabilityHandler {

    private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
            "spawn_subagent",
            "Delegate a self-contained task to a child agent and get back only its conclusion. "
                    + "Use when the work would otherwise fill your context with detail you will "
                    + "not need afterwards. The child cannot do anything you could not.",
            EffectClass.PROCESS,
            Schemas.object(
                    Schemas.properties(
                            "description", Schemas.string("Short label for the delegated task."),
                            "prompt", Schemas.string(
                                    "The full task. The child sees only this - it does not "
                                            + "inherit your conversation.")),
                    List.of("prompt")));

    private final SubagentHost host;
    private final boolean async;

    public SubagentTool(SubagentHost host) {
        this(host, false);
    }

    /**
     * @param async when true the child is queued for a worker and the parent parks on a process
     *              gate instead of blocking its tool call; requires a running worker or server
     */
    public SubagentTool(SubagentHost host, boolean async) {
        this.host = Objects.requireNonNull(host, "host");
        this.async = async;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
        String prompt = invocation.stringArg("prompt", "");
        if (prompt.isBlank()) {
            return Result.err(HandlerError.failed("prompt_required"));
        }
        String description = invocation.stringArg("description", "subtask");

        if (async) {
            return host.spawnAsync(invocation.scope(), description, prompt)
                    .mapErr(HandlerError::failed)
                    .flatMap(progress -> switch (progress) {
                        case SubagentHost.Progress.Running running -> Result.err(HandlerError.waiting(
                                running.child().value(),
                                "subagent '" + description + "' running as " + running.child().value()));
                        case SubagentHost.Progress.Finished finished -> Result.ok(
                                finished.result().succeeded()
                                        ? finished.result().reply()
                                        : "The subagent did not complete: " + finished.result().reply());
                    });
        }

        // Depth is derived from the scope by the host, so a model cannot claim to be shallower
        // than it is by passing a smaller number.
        return host.spawn(invocation.scope(), description, prompt, 0)
                .mapErr(HandlerError::failed)
                .map(result -> result.succeeded()
                        ? result.reply()
                        : "The subagent did not complete: " + result.reply());
    }
}
