// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.SubagentHost;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs subagents as ordinary child turns through {@link JclawRuntime}.
 *
 * <p>No separate execution path: a child goes through the same turn machine, effect interpreter,
 * capability host, and exit validation as any other run. That is the whole design constraint —
 * a private subagent engine would be a second place for authority, checkpointing, and audit to
 * diverge from the real one.
 *
 * <p>The child is admitted under the <em>parent's</em> tenant. That is the isolation key: it
 * chooses the secret vault the kernel hands a lane, scopes the approval gates the child raises,
 * and names the budget its tokens are charged to. Admitting a child as the local tenant would let
 * a hosted tenant's agent reach the operator's credentials by delegating.
 *
 * <p>Depth is encoded in the child's thread id ({@code parent~sub}) and read back from it, rather
 * than passed as a parameter. A depth the caller supplies is a depth the caller can understate;
 * a depth derived from the scope cannot be forged by the model.
 *
 * <p>The runtime arrives as an {@link ObjectProvider} because the wiring is genuinely cyclic:
 * capability handlers need this host, the capability host needs the handlers, and the runtime needs
 * the capability host. The cycle is only in construction — this host needs a runtime when it
 * <em>spawns</em>, not when it is built — so a deferred lookup states that honestly. Enabling
 * {@code spring.main.allow-circular-references} would paper over it and leave the initialization
 * order undefined.
 */
@Service
public class RuntimeSubagentHost implements SubagentHost {

    /** Marks a thread as a subagent of the thread before it. */
    private static final String DEPTH_MARKER = "~sub";

    private final ObjectProvider<JclawRuntime> runtime;
    private final RunStore runs;
    private final ThreadService threads;

    public RuntimeSubagentHost(ObjectProvider<JclawRuntime> runtime, RunStore runs, ThreadService threads) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.threads = Objects.requireNonNull(threads, "threads");
    }

    @Override
    public Result<SubagentResult, String> spawn(
            TurnScope parentScope, String description, String prompt, int ignoredDepth) {

        Objects.requireNonNull(parentScope, "parentScope");
        Objects.requireNonNull(prompt, "prompt");

        int depth = depthOf(parentScope.thread());
        if (depth >= MAX_DEPTH) {
            return Result.err("subagent_depth_exceeded");
        }

        ThreadId childThread = childThread(parentScope.thread(), depth, prompt);

        // The parent's tenant, not the CLI's. The tenant selects the secret vault at dispatch,
        // scopes approvals, and is what the token ledger charges, so a child admitted as "local"
        // would run a tenant's work holding the operator's credentials.
        JclawRuntime.TurnResult result = runtime.getObject().submit(
                parentScope.tenant(), childThread, ChatMessage.user(prompt),
                new AtomicBoolean(false), Optional.empty());

        return Result.ok(new SubagentResult(
                result.reply().orElseGet(() -> "status: " + result.status()
                        + result.failure().map(kind -> " (" + kind.category() + ")").orElse("")),
                result.isSuccess(),
                result.tokensSpent()));
    }

    /**
     * Asynchronous form: enqueue the child once, then report on it.
     *
     * <p>The child thread is a pure function of the parent thread, the depth, and the task, so a
     * re-dispatched invocation finds the same child. The most recent run on that thread is the
     * child's state: absent means start one, terminal means report it, anything else means still
     * running.
     */
    @Override
    public Result<Progress, String> spawnAsync(TurnScope parentScope, String description, String prompt) {
        Objects.requireNonNull(parentScope, "parentScope");
        Objects.requireNonNull(prompt, "prompt");

        int depth = depthOf(parentScope.thread());
        if (depth >= MAX_DEPTH) {
            return Result.err("subagent_depth_exceeded");
        }
        ThreadId childThread = childThread(parentScope.thread(), depth, prompt);

        Optional<RunStore.RunRecord> latest = runs.recent(Integer.MAX_VALUE).stream()
                .filter(record -> record.scope().thread().equals(childThread))
                .max(Comparator.comparing(RunStore.RunRecord::submittedAt));
        if (latest.isEmpty()) {
            TurnRunId child;
            try {
                child = runtime.getObject().enqueue(
                        parentScope.tenant(), childThread, ChatMessage.user(prompt));
            } catch (JclawRuntime.TenantOverBudget overBudget) {
                // The synchronous path reports this as a failed turn rather than throwing, so
                // report it the same way here instead of letting it surface as "handler_threw".
                return Result.err("subagent_tenant_over_budget");
            }
            return Result.ok(new Progress.Running(child));
        }
        RunStore.RunRecord child = latest.get();
        if (!child.status().isTerminal()) {
            return Result.ok(new Progress.Running(child.run()));
        }
        String reply = threads.history(childThread, Integer.MAX_VALUE).stream()
                .map(ThreadService.ThreadMessage::message)
                .filter(message -> message.role() == ChatMessage.Role.ASSISTANT)
                .reduce((first, second) -> second)
                .map(ChatMessage::displayText)
                .orElse("status: " + child.status());
        return Result.ok(new Progress.Finished(child.run(), new SubagentResult(
                reply, child.status() == TurnStatus.COMPLETED, 0)));
    }

    /**
     * A fresh child thread: the subagent gets the prompt and nothing else. Inheriting the
     * parent's transcript would defeat the purpose, which is to keep detail out of it.
     */
    private static ThreadId childThread(ThreadId parent, int depth, String prompt) {
        return new ThreadId(parent.value() + DEPTH_MARKER + (depth + 1) + "-"
                + Integer.toHexString(prompt.hashCode()));
    }

    /** The parent thread of a subagent thread, if it is one. */
    public static Optional<ThreadId> parentOf(ThreadId thread) {
        int marker = thread.value().lastIndexOf(DEPTH_MARKER);
        return marker <= 0 ? Optional.empty() : Optional.of(new ThreadId(thread.value().substring(0, marker)));
    }

    /** Counts the depth markers in a thread id. A top-level thread has none. */
    static int depthOf(ThreadId thread) {
        int depth = 0;
        int index = thread.value().indexOf(DEPTH_MARKER);
        while (index >= 0) {
            depth++;
            index = thread.value().indexOf(DEPTH_MARKER, index + DEPTH_MARKER.length());
        }
        return depth;
    }
}
