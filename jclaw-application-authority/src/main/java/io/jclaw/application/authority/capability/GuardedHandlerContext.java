// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.application.authority.capability;

import io.jclaw.ports.Result;
import io.jclaw.ports.capability.CapabilityHandler;
import io.jclaw.application.authority.guard.EgressGuard;
import io.jclaw.application.authority.guard.WorkspaceGuard;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The scoped services a runtime lane receives, backed by the host's guards.
 *
 * <p>This is the whole surface a tool gets. It is short on purpose: a lane can resolve a path, check
 * a URL, render a path for display, and learn its output budget. It cannot obtain a secret, widen
 * its own workspace, or reach the network by another route — not because it is asked not to, but
 * because there is no method for it.
 */
public final class GuardedHandlerContext implements CapabilityHandler.HandlerContext {

    /** Default ceiling on lane output before the kernel truncates. */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

    private final WorkspaceGuard workspace;
    private final EgressGuard egress;
    private final int maxOutputBytes;

    public GuardedHandlerContext(WorkspaceGuard workspace, EgressGuard egress, int maxOutputBytes) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.egress = Objects.requireNonNull(egress, "egress");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
    }

    public GuardedHandlerContext(WorkspaceGuard workspace, EgressGuard egress) {
        this(workspace, egress, DEFAULT_MAX_OUTPUT_BYTES);
    }

    @Override
    public Result<Path, String> resolvePath(String candidate) {
        return workspace.resolve(candidate);
    }

    @Override
    public Result<URI, String> checkEgress(String url) {
        return egress.check(url);
    }

    @Override
    public String displayPath(Path path) {
        return workspace.display(path);
    }

    @Override
    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    /** The workspace root, for callers that need to report it. */
    public Path workspaceRoot() {
        return workspace.root();
    }
}
