package io.jclaw.contracts.loop;

import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.CapabilityOutcome;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;

import java.util.Objects;

/**
 * An execution-stage hook: host code that observes, narrows, or vetoes the loop's two kinds of
 * effect just before and after they happen.
 *
 * <p>Hooks are how the host adds behaviour to every run without touching the machine: a notice
 * when the budget runs low, an audit of tool arguments, a veto on a call the operator's policy
 * cannot express. They are trusted as host code, and constrained accordingly: a hook may make a
 * model request smaller (fewer tools, an amended system prompt) but never larger, may rewrite a
 * capability's arguments but never its identity, and may veto either. The kernel still runs
 * every check on what a hook hands back, so a hook cannot grant what policy would refuse.
 *
 * <p>Every method has a do-nothing default so a hook implements only the stages it cares about.
 */
public interface LoopHook {

    /** A stable name for logs and the audit event. */
    String id();

    /**
     * What a hook knows about the run at the moment it is called.
     *
     * @param budgetUsed fraction of the token budget spent so far, 0 to 1
     */
    record HookContext(TurnRunId run, TurnScope scope, int iteration, double budgetUsed) {
        public HookContext {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(scope, "scope");
            if (budgetUsed < 0 || budgetUsed > 1) {
                throw new IllegalArgumentException("budgetUsed must be between 0 and 1");
            }
        }
    }

    /** A hook's answer: proceed with (possibly rewritten) input, or stop with a reason. */
    sealed interface Outcome<T> {
        record Proceed<T>(T value) implements Outcome<T> {
            public Proceed {
                Objects.requireNonNull(value, "value");
            }
        }

        record Veto<T>(String reason) implements Outcome<T> {
            public Veto {
                Objects.requireNonNull(reason, "reason");
                if (reason.isBlank()) {
                    throw new IllegalArgumentException("a veto needs a reason");
                }
            }
        }

        static <T> Outcome<T> proceed(T value) {
            return new Proceed<>(value);
        }

        static <T> Outcome<T> veto(String reason) {
            return new Veto<>(reason);
        }
    }

    /** Before a model call. The request may be narrowed, never widened. */
    default Outcome<ModelRequest> beforeModel(HookContext context, ModelRequest request) {
        return Outcome.proceed(request);
    }

    /** After a successful model call. Observation only. */
    default void afterModel(HookContext context, ModelRequest request, ModelResponse response) {
    }

    /** Before a capability is dispatched to the kernel. Arguments may change; the capability may not. */
    default Outcome<CapabilityInvocation> beforeCapability(HookContext context, CapabilityInvocation invocation) {
        return Outcome.proceed(invocation);
    }

    /** After the kernel returned an outcome. Observation only. */
    default void afterCapability(HookContext context, CapabilityInvocation invocation, CapabilityOutcome outcome) {
    }
}
