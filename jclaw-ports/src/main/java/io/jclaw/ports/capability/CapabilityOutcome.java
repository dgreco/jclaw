// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.capability;

import io.jclaw.ports.loop.GateKind;
import io.jclaw.ports.turn.TurnRef.LoopGateRef;
import io.jclaw.ports.turn.TurnRef.LoopResultRef;

import java.util.Objects;
import java.util.Optional;

/**
 * What came back from a capability invocation, after the kernel sanitized it.
 *
 * <p>The loop sees this, never the raw runtime output. A handler may have produced megabytes, or
 * a host path, or a provider stack trace; by the time it reaches here it is a durable
 * {@link LoopResultRef} plus a bounded summary safe to hand to a model. The full payload stays in
 * the result store, reachable through the ref by anything authorized to read it.
 */
public sealed interface CapabilityOutcome {

    /**
     * The capability ran and produced a result.
     *
     * @param resultRef durable handle to the full payload
     * @param summary   bounded, redacted text suitable for the model's tool-result block
     * @param truncated whether {@code summary} omits content present in the stored result
     */
    record Ok(LoopResultRef resultRef, String summary, boolean truncated) implements CapabilityOutcome {
        public Ok {
            Objects.requireNonNull(resultRef, "resultRef");
            Objects.requireNonNull(summary, "summary");
        }
    }

    /**
     * Policy refused the invocation. Not an error — a decision.
     *
     * @param reason stable category, e.g. {@code capability_not_authorized}, {@code path_outside_workspace}
     */
    record Denied(String reason, Optional<String> detail) implements CapabilityOutcome {
        public Denied {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }

        public static Denied of(String reason) {
            return new Denied(reason, Optional.empty());
        }

        public static Denied of(String reason, String detail) {
            return new Denied(reason, Optional.ofNullable(detail));
        }
    }

    /**
     * The capability was authorized and dispatched, but its runtime lane failed.
     *
     * @param category stable redacted category; never the underlying exception text
     */
    record Failed(String category, Optional<String> detail) implements CapabilityOutcome {
        public Failed {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(detail, "detail");
        }

        public static Failed of(String category) {
            return new Failed(category, Optional.empty());
        }

        public static Failed of(String category, String detail) {
            return new Failed(category, Optional.ofNullable(detail));
        }
    }

    /**
     * The invocation needs a human before it may proceed. The loop must checkpoint and return
     * {@link io.jclaw.ports.loop.LoopExit.Blocked} — it must not retry, and it must not
     * proceed as though denied.
     */
    record NeedsApproval(GateKind gate, LoopGateRef gateRef, String prompt) implements CapabilityOutcome {
        public NeedsApproval {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(gateRef, "gateRef");
            Objects.requireNonNull(prompt, "prompt");
        }
    }

    /** Whether the loop may continue its tick cycle with this outcome in hand. */
    default boolean allowsContinue() {
        return !(this instanceof NeedsApproval);
    }

    /**
     * Text to feed back to the model as a tool result. Every branch produces something — the model
     * is told that a call was denied or failed, because silently dropping the result leaves it
     * confused about what happened and prone to retrying forever.
     */
    default String modelFacingText() {
        return switch (this) {
            case Ok ok -> ok.truncated() ? ok.summary() + "\n[truncated]" : ok.summary();
            case Denied denied -> "Denied: " + denied.reason()
                    + denied.detail().map(d -> " (" + d + ")").orElse("");
            case Failed failed -> "Failed: " + failed.category()
                    + failed.detail().map(d -> " (" + d + ")").orElse("");
            case NeedsApproval approval -> "Awaiting approval: " + approval.prompt();
        };
    }

    /** Whether this outcome represents an error from the model's point of view. */
    default boolean isError() {
        return this instanceof Denied || this instanceof Failed;
    }
}
