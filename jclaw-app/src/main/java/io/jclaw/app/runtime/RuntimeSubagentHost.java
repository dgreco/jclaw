package io.jclaw.app.runtime;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.SubagentHost;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs subagents as ordinary child turns through {@link JclawRuntime}.
 *
 * <p>No separate execution path: a child goes through the same turn machine, effect interpreter,
 * capability host, and exit validation as any other run. That is the whole design constraint —
 * a private subagent engine would be a second place for authority, checkpointing, and audit to
 * diverge from the real one.
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

    public RuntimeSubagentHost(ObjectProvider<JclawRuntime> runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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

        // A fresh child thread: the subagent gets the prompt and nothing else. Inheriting the
        // parent's transcript would defeat the purpose, which is to keep detail out of it.
        ThreadId childThread = new ThreadId(
                parentScope.thread().value() + DEPTH_MARKER + (depth + 1) + "-"
                        + Integer.toHexString(prompt.hashCode()));

        JclawRuntime.TurnResult result =
                runtime.getObject().submit(childThread, prompt, new AtomicBoolean(false));

        return Result.ok(new SubagentResult(
                result.reply().orElseGet(() -> "status: " + result.status()
                        + result.failure().map(kind -> " (" + kind.category() + ")").orElse("")),
                result.isSuccess(),
                result.tokensSpent()));
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
