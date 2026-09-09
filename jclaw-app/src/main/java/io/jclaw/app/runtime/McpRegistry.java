package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.TrustClass;
import io.jclaw.contracts.mcp.McpServerStore;
import io.jclaw.tools.mcp.McpCapabilityHandler;
import io.jclaw.tools.mcp.McpClient;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Connects to enabled MCP servers at startup and registers their tools as capabilities.
 *
 * <p>Connecting means spawning a process per server, so this only does anything when servers are
 * actually configured — the common case is zero, and a CLI that forked processes on every
 * invocation to discover nothing would be intolerable.
 *
 * <p>A server that fails to start is skipped with a warning rather than failing the harness. An
 * external tool provider being unavailable is an ordinary operating condition; refusing to run the
 * agent because an optional integration is down would be the wrong trade.
 */
@Service
public class McpRegistry {

    private final List<McpClient> clients = new ArrayList<>();
    private final List<CapabilityHandler> handlers = new ArrayList<>();

    public McpRegistry(McpServerStore servers, Path workspaceRoot) {
        this(servers, workspaceRoot, java.util.Optional.empty());
    }

    /** @param sandbox when present, every server process runs inside this container contract */
    public McpRegistry(McpServerStore servers, Path workspaceRoot,
            java.util.Optional<io.jclaw.domain.sandbox.SandboxSpec> sandbox) {
        this(servers, workspaceRoot, sandbox, null);
    }

    /**
     * @param extensions installed extensions; every enabled MCP extension is started beside the
     *                   servers from {@code mcp add}, and its tools carry the trust and effect the
     *                   installation earned
     */
    public McpRegistry(McpServerStore servers, Path workspaceRoot,
            java.util.Optional<io.jclaw.domain.sandbox.SandboxSpec> sandbox,
            io.jclaw.contracts.extension.ExtensionRegistry extensions) {
        Objects.requireNonNull(servers, "servers");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(sandbox, "sandbox");

        for (McpServerStore.McpServer server : servers.list()) {
            if (!server.enabled()) {
                continue;
            }
            McpClient.start(server.name(), server.command(), server.env(), workspaceRoot, sandbox)
                    .fold(
                            client -> register(server, client, TrustClass.COMMUNITY, EffectClass.NETWORK),
                            reason -> warn(server, reason));
        }
        if (extensions != null) {
            for (var installed : extensions.list()) {
                if (!installed.enabled() || installed.manifest().kind() != io.jclaw.contracts.extension.ExtensionRegistry.Kind.MCP) {
                    continue;
                }
                McpServerStore.McpServer server = new McpServerStore.McpServer(
                        installed.name(), installed.manifest().command(), installed.env(), true);
                McpClient.start(server.name(), server.command(), server.env(), workspaceRoot, sandbox)
                        .fold(
                                client -> register(server, client, installed.trust(), installed.effectiveEffect()),
                                reason -> warn(server, reason));
            }
        }
    }

    private boolean register(McpServerStore.McpServer server, McpClient client, TrustClass trust, EffectClass effect) {
        return McpCapabilityHandler.handlersFor(client, trust, effect).fold(
                discovered -> {
                    clients.add(client);
                    handlers.addAll(discovered);
                    return true;
                },
                reason -> {
                    client.close();
                    return warn(server, reason);
                });
    }

    private static boolean warn(McpServerStore.McpServer server, String reason) {
        System.err.println("jclaw: MCP server '" + server.name() + "' unavailable (" + reason + ")");
        return false;
    }

    /** Capabilities discovered from connected servers. Empty when none are configured. */
    public List<CapabilityHandler> handlers() {
        return List.copyOf(handlers);
    }

    /** Number of servers currently connected. */
    public int connectedServers() {
        return clients.size();
    }

    /**
     * Stops every server process.
     *
     * <p>Bound to context shutdown rather than left to the JVM: an MCP server is a child process,
     * and a CLI that leaked one per invocation would accumulate them silently.
     */
    @PreDestroy
    public void shutdown() {
        clients.forEach(McpClient::close);
        clients.clear();
    }
}
