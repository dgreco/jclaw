// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.capability;

import io.jclaw.contracts.Result;

/**
 * A runtime lane: the code that actually performs a capability.
 *
 * <p>Handlers sit <em>below</em> the authority boundary. By the time one is called the kernel has
 * already established that this exact invocation is permitted, so a handler must not re-implement
 * policy, and — more importantly — must not assume it can relax it. A handler that decides for
 * itself that a path is fine has stepped outside the security model.
 *
 * <p>What a handler owns is execution and the local invariants of its own lane: bounding output
 * size, honouring a timeout, refusing malformed arguments. What it does not own is whether the
 * call should happen at all.
 *
 * <p>Handlers return {@link Result} rather than throwing, so a failing tool is ordinary data the
 * loop can feed back to the model instead of an exception unwinding the run.
 */
public interface CapabilityHandler {

    /** What this handler implements, including its host-assigned effect and trust classes. */
    CapabilityDescriptor descriptor();

    /**
     * Executes an already-authorized invocation.
     *
     * @param invocation the exact call, with arguments as supplied by the model
     * @param context    scoped host services the lane is permitted to use
     * @return the raw payload on success, or a typed {@link HandlerError} distinguishing a
     *         policy denial from an execution failure. The kernel bounds, redacts, and stores
     *         the payload afterwards — a handler returns content, not refs.
     */
    Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context);

    /**
     * Scoped services handed to a lane.
     *
     * <p>Narrow on purpose. A handler receives the guards it needs to do its job and nothing else —
     * in particular no secret vault, because tool lanes must never hold credentials. Secrets are
     * leased at the provider boundary for one handoff and consumed there.
     */
    interface HandlerContext {

        /**
         * Resolves a caller-supplied path inside the workspace, or returns a denial reason.
         * The only sanctioned way for a lane to turn a string into a filesystem path.
         */
        Result<java.nio.file.Path, String> resolvePath(String candidate);

        /** Validates an outbound URL against host egress policy. */
        Result<java.net.URI, String> checkEgress(String url);

        /** Renders a path for display without leaking the host layout. */
        String displayPath(java.nio.file.Path path);

        /** Maximum bytes a lane should return; larger payloads are truncated by the kernel. */
        int maxOutputBytes();

        /**
         * Credentials the kernel released into <em>this one call</em>, for a lane that starts a
         * child process to put into its environment.
         *
         * <p>This is not a vault handle and cannot become one. It is a map of values the kernel
         * already decided to release, for this invocation only, chosen by the staging request in
         * the arguments and permitted by each secret's binding. A lane cannot ask for a secret it
         * was not given, cannot ask again, and cannot enumerate what exists — which is the whole
         * reason a lane is not allowed to hold the vault.
         *
         * <p>Empty for every lane that does not spawn anything, which is nearly all of them.
         */
        default java.util.Map<String, String> stagedEnvironment() {
            return java.util.Map.of();
        }
    }
}
