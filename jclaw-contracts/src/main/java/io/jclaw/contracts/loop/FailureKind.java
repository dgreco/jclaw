// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.loop;

/**
 * Stable, redacted failure categories.
 *
 * <p>This enum is the <em>entire</em> public failure vocabulary. Provider stack traces, request
 * payloads, credentials, host paths, and backend diagnostics never reach turn records or public
 * events — they are logged host-side and collapsed to one of these categories on the way out.
 *
 * <p>The categories are deliberately coarse. A caller deciding whether to retry needs to know
 * "the provider failed" and not which TLS cipher was rejected; anything finer is an information
 * leak dressed up as helpfulness.
 */
public enum FailureKind {

    /** The driver returned an exit whose evidence did not validate. */
    DRIVER_PROTOCOL_VIOLATION("driver_protocol_violation", false),

    /** The run stopped without a durable terminal claim. */
    INTERRUPTED_UNEXPECTEDLY("interrupted_unexpectedly", false),

    /** A runner lease expired and the latest checkpoint could not prove side-effect freedom. */
    LEASE_EXPIRED("lease_expired", false),

    /** The model provider failed after the loop's own retry budget was spent. */
    PROVIDER_ERROR("provider_error", true),

    /** Every provider in the failover chain was unavailable. */
    PROVIDER_UNAVAILABLE("provider_unavailable", true),

    /** The run exhausted its token, wall-clock, or iteration budget. */
    BUDGET_EXHAUSTED("budget_exhausted", false),

    /** A capability request was denied by policy and the loop could not proceed without it. */
    POLICY_DENIED("policy_denied", false),

    /** A capability invocation failed inside its runtime lane. */
    CAPABILITY_FAILED("capability_failed", true),

    /**
     * The provider rejected the request as malformed or unacceptable.
     *
     * <p>Distinct from {@link #INTERNAL}: the cause is usually configuration a user can fix — an
     * unknown model id, an unsupported parameter — not a host defect. Labelling it "internal" sent
     * people looking for a bug in the harness when the answer was a typo in their model name.
     */
    INVALID_REQUEST("invalid_request", false),

    /** The loop produced no reply and had nothing further to do. */
    NO_PROGRESS("no_progress", false),

    /**
     * Another run is active on the same thread, so this one was refused at admission.
     *
     * <p>Nothing was recorded: no inbound message, no run. Retryable in the plain sense, since
     * the same submission succeeds once the other run finishes.
     */
    THREAD_BUSY("thread_busy", true),

    /** Host-side defect: a store, port, or invariant failed. */
    INTERNAL("internal", true);

    private final String category;
    private final boolean retryable;

    FailureKind(String category, boolean retryable) {
        this.category = category;
        this.retryable = retryable;
    }

    /** The wire/audit token. Stable across releases — clients may switch on it. */
    public String category() {
        return category;
    }

    /**
     * Whether resubmitting the same turn could plausibly succeed. Advisory for the product
     * surface; it never causes automatic retry of side-effecting work.
     */
    public boolean isRetryable() {
        return retryable;
    }

    public static FailureKind fromCategory(String category) {
        for (FailureKind kind : values()) {
            if (kind.category.equals(category)) {
                return kind;
            }
        }
        return INTERNAL;
    }
}
