// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.kernel.capability;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.kernel.guard.EgressGuard;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A handler context narrowed to one capability's egress allowlist.
 *
 * <p>Wraps the host-wide context rather than replacing it: the base check (scheme, credentials,
 * metadata hosts, private networks, the global lists) runs first and is never bypassed, and only
 * then is the per-tool allowlist consulted. A per-tool list can therefore only narrow what a tool
 * may reach, exactly as the global list can only narrow the guard's posture.
 *
 * <p>Everything else is delegated untouched. A tool gains no method it did not have.
 */
final class ToolScopedContext implements CapabilityHandler.HandlerContext {

    private final CapabilityHandler.HandlerContext base;
    private final EgressGuard toolAllowlist;

    ToolScopedContext(CapabilityHandler.HandlerContext base, Set<String> allowedHosts) {
        this.base = Objects.requireNonNull(base, "base");
        // Address-family checks were done by the base guard; this guard is only the list.
        this.toolAllowlist = EgressGuard.allowingPrivateNetworks().withAllowlist(allowedHosts);
    }

    @Override
    public Result<Path, String> resolvePath(String candidate) {
        return base.resolvePath(candidate);
    }

    @Override
    public Result<URI, String> checkEgress(String url) {
        Result<URI, String> host = base.checkEgress(url);
        if (host.isErr()) {
            return host;
        }
        return toolAllowlist.check(url).mapErr(reason -> "tool_" + reason);
    }

    @Override
    public String displayPath(Path path) {
        return base.displayPath(path);
    }

    @Override
    public int maxOutputBytes() {
        return base.maxOutputBytes();
    }

    @Override
    public Map<String, String> stagedEnvironment() {
        // Delegated, not defaulted: inheriting the empty default here would silently drop a
        // staged credential for exactly the tools an operator bothered to narrow.
        return base.stagedEnvironment();
    }
}
