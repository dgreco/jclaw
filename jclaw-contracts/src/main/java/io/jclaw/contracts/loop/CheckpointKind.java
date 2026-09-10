// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.loop;

/**
 * Where in the tick pipeline a checkpoint was taken.
 *
 * <p>This is not bookkeeping — it is the safety predicate for lease recovery. When a runner dies
 * mid-run, the reconciler must decide whether replaying from the last checkpoint could duplicate
 * a side effect that already escaped to the outside world. That decision is made <em>solely</em>
 * from {@link #replaysNoSideEffect()}.
 *
 * <p>The default answer is no. An unknown or newly added checkpoint kind must be treated as
 * unsafe, which is why the flag is set per-constant rather than derived: adding a constant
 * without thinking forces you to state the safety claim explicitly.
 */
public enum CheckpointKind {

    /**
     * Parked immediately before a model call. Nothing has been sent to a provider, so replaying
     * re-issues a request that never happened. Safe.
     */
    BEFORE_MODEL(true),

    /**
     * Parked immediately before raising an approval/auth gate. No effect has been dispatched.
     * Safe.
     */
    BEFORE_BLOCK(true),

    /**
     * Parked before invoking a capability. The invocation has been authorized but not dispatched;
     * the authorization lease is exact-invocation scoped and re-authorization is required on
     * resume, so replay does not silently reuse authority. Safe.
     */
    BEFORE_CAPABILITY(true),

    /**
     * Parked after a capability returned. The effect has already happened — a file was written,
     * a request was sent. Replaying could duplicate it. Unsafe.
     */
    AFTER_CAPABILITY(false),

    /**
     * Parked after a model call returned but before the response was applied. Tokens were spent
     * and the provider may have performed server-side effects. Unsafe.
     */
    AFTER_MODEL(false),

    /** Parked at an unclassified point. Fails closed. */
    UNKNOWN(false);

    private final boolean replaysNoSideEffect;

    CheckpointKind(boolean replaysNoSideEffect) {
        this.replaysNoSideEffect = replaysNoSideEffect;
    }

    /**
     * Whether resuming from this checkpoint is guaranteed to repeat no externally visible effect.
     * Only checkpoints returning {@code true} may be requeued after lease expiry; everything else
     * becomes a terminal sanitized failure and the user resubmits explicitly.
     */
    public boolean replaysNoSideEffect() {
        return replaysNoSideEffect;
    }
}
