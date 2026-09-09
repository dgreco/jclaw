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
import java.util.Map;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpRegistry.class);

    private final List<McpClient> clients = new ArrayList<>();
    private final List<CapabilityHandler> handlers = new ArrayList<>();
    private final io.jclaw.kernel.guard.EgressGuard egress;
    private final io.jclaw.contracts.secret.SecretVault vault;
    private final io.jclaw.storage.mcp.McpSurfaceCache cache;
    private final java.time.Clock clock = java.time.Clock.systemUTC();
    private java.util.function.Function<String, io.jclaw.tools.mcp.McpTransport.ServerRequests>
            samplingFor = server -> null;

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
        this(servers, workspaceRoot, sandbox, extensions, null,
                io.jclaw.contracts.secret.SecretVault.empty(), null);
    }

    /**
     * @param egress guard every remote server's endpoint is checked against, so an MCP URL cannot
     *               reach a private address any more than a tool's URL can
     * @param vault  where an HTTP server's bearer token comes from: the secret named by the
     *               server's {@code authSecret}, bound to {@code mcp.connect} and the endpoint's
     *               host. The token is handed to the transport as a header value; no code below
     *               the app layer sees the vault
     */
    public McpRegistry(McpServerStore servers, Path workspaceRoot,
            java.util.Optional<io.jclaw.domain.sandbox.SandboxSpec> sandbox,
            io.jclaw.contracts.extension.ExtensionRegistry extensions,
            io.jclaw.kernel.guard.EgressGuard egress,
            io.jclaw.contracts.secret.SecretVault vault) {
        this(servers, workspaceRoot, sandbox, extensions, egress, vault, null);
    }

    /**
     * @param cache remembered surfaces; when a server's cached surface matches its current
     *              configuration, its capabilities are published from the cache and the server is
     *              not started until one of them is invoked. Null discovers everything eagerly
     */
    public McpRegistry(McpServerStore servers, Path workspaceRoot,
            java.util.Optional<io.jclaw.domain.sandbox.SandboxSpec> sandbox,
            io.jclaw.contracts.extension.ExtensionRegistry extensions,
            io.jclaw.kernel.guard.EgressGuard egress,
            io.jclaw.contracts.secret.SecretVault vault,
            io.jclaw.storage.mcp.McpSurfaceCache cache) {
        Objects.requireNonNull(servers, "servers");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(sandbox, "sandbox");
        this.egress = egress;
        this.vault = Objects.requireNonNull(vault, "vault");
        this.cache = cache;

        for (McpServerStore.McpServer server : servers.list()) {
            if (!server.enabled()) {
                continue;
            }
            bring(server, workspaceRoot, sandbox, java.util.Set.of(),
                    TrustClass.COMMUNITY, EffectClass.NETWORK);
        }
        if (extensions != null) {
            for (var installed : extensions.list()) {
                if (!installed.enabled() || installed.manifest().kind() != io.jclaw.contracts.extension.ExtensionRegistry.Kind.MCP) {
                    continue;
                }
                McpServerStore.McpServer server = new McpServerStore.McpServer(
                        installed.name(), installed.manifest().command(), installed.secrets(), true);
                // A verified package signed its host claim, so it is worth binding a secret to.
                bring(server, workspaceRoot, sandbox, java.util.Set.copyOf(installed.manifest().hosts()),
                        installed.trust(), installed.effectiveEffect());
            }
        }
    }

    /**
     * Opens a connection: a child process for a stdio server, an HTTP transport for a remote one.
     *
     * <p>A remote endpoint goes through the egress guard first — an MCP URL is operator
     * configuration, but it is still a URL the host is about to open, and the same private-network
     * and metadata rules apply. Its bearer token, when configured, is leased from the vault and
     * only if the secret's binding names {@code mcp.connect} and that endpoint's host.
     */
    private io.jclaw.contracts.Result<io.jclaw.tools.mcp.McpTransport, String> transportFor(
            McpServerStore.McpServer server, Path workspaceRoot,
            java.util.Optional<io.jclaw.domain.sandbox.SandboxSpec> sandbox,
            java.util.Set<String> declaredHosts) {

        if (!server.isHttp()) {
            // The store holds secret names; the values are leased here and live only in the
            // environment of the process about to start.
            return McpCredentials.resolve(server.envSecrets(), declaredHosts, vault)
                    .flatMap(environment -> io.jclaw.tools.mcp.StdioTransport.start(
                            server.command(), environment, workspaceRoot, sandbox));
        }
        if (egress == null) {
            return io.jclaw.contracts.Result.err("http_transport_needs_an_egress_guard");
        }
        io.jclaw.contracts.Result<java.net.URI, String> checked = egress.check(server.url());
        if (checked.isErr()) {
            return io.jclaw.contracts.Result.err("endpoint_" + checked.errorAsOptional().orElse("denied"));
        }
        java.net.URI endpoint = checked.orElseThrow();

        // OAuth 2.1, when configured: a supplier rather than a value, because an access token
        // expires and the header has to be current when the request is built.
        if (server.oauth().isPresent()) {
            io.jclaw.app.mcp.McpOAuth oauth = new io.jclaw.app.mcp.McpOAuth(
                    server.oauth().get(), server.url(), vault, egress, clock);
            return io.jclaw.contracts.Result.ok(
                    new io.jclaw.tools.mcp.HttpTransport(endpoint, oauth::header));
        }

        java.util.Optional<String> authorization;
        if (server.authSecret().isBlank()) {
            authorization = java.util.Optional.empty();
        } else {
            var name = new io.jclaw.contracts.secret.SecretVault.SecretName(server.authSecret());
            var lease = vault.lease(name);
            if (lease.isEmpty()) {
                return io.jclaw.contracts.Result.err("auth_secret_unknown");
            }
            var refusal = io.jclaw.domain.secret.SecretInjection.refuse(
                    lease.get().info().binding(), CONNECT,
                    java.util.Set.of(endpoint.getHost() == null ? "" : endpoint.getHost()));
            if (refusal.isPresent()) {
                return io.jclaw.contracts.Result.err(refusal.get());
            }
            authorization = java.util.Optional.of("Bearer " + lease.get().value());
        }
        return io.jclaw.contracts.Result.ok(new io.jclaw.tools.mcp.HttpTransport(endpoint, authorization));
    }

    /**
     * Registers one server, from the cache when it has an entry for this exact configuration and
     * from a live handshake otherwise. A live discovery updates the cache, so the next process
     * starts nothing.
     */
    private void bring(
            McpServerStore.McpServer server, Path workspaceRoot,
            java.util.Optional<io.jclaw.domain.sandbox.SandboxSpec> sandbox,
            java.util.Set<String> declaredHosts,
            TrustClass trust, EffectClass effect) {

        String fingerprint = fingerprint(server);
        var cached = cache == null
                ? java.util.Optional.<io.jclaw.storage.mcp.McpSurfaceCache.Surface>empty()
                : cache.find(server.name(), fingerprint);
        if (cached.isPresent()) {
            McpClient client = McpClient.deferred(server.name(), cached.get().offers(),
                    () -> transportFor(server, workspaceRoot, sandbox, declaredHosts));
            withSampling(server.name(), client);
            clients.add(client);
            for (Map<String, Object> tool : cached.get().tools()) {
                handlers.add(new McpCapabilityHandler(client, toTool(tool), trust, effect));
            }
            handlers.addAll(io.jclaw.tools.mcp.McpSurfaceTools.handlersFor(client, trust, effect));
            log.debug("mcp: {} published {} tool(s) from cache; not started", server.name(),
                    cached.get().tools().size());
            return;
        }
        transportFor(server, workspaceRoot, sandbox, declaredHosts)
                .flatMap(transport -> connectWithSampling(server.name(), transport))
                .fold(
                        client -> {
                            boolean registered = register(server, client, trust, effect);
                            if (registered && cache != null) {
                                client.listTools().toOptional().ifPresent(tools -> cache.put(
                                        server.name(), fingerprint, client.offers(),
                                        tools.stream().map(McpRegistry::fromTool).toList()));
                            }
                            return registered;
                        },
                        reason -> warn(server, reason));
    }

    /**
     * Lets a server answer for itself: sampling, when the operator enabled it.
     *
     * <p>A handler per server rather than one shared, because the cap is per server. A server in
     * a loop should exhaust its own budget, not everyone's.
     */
    public void withSamplingHandlers(
            java.util.function.Function<String, io.jclaw.tools.mcp.McpTransport.ServerRequests> factory) {
        this.samplingFor = java.util.Objects.requireNonNull(factory, "factory");
    }

    private void withSampling(String server, McpClient client) {
        io.jclaw.tools.mcp.McpTransport.ServerRequests handler = samplingFor.apply(server);
        if (handler != null) {
            client.withSampling(handler);
        }
    }

    private io.jclaw.contracts.Result<McpClient, String> connectWithSampling(
            String server, io.jclaw.tools.mcp.McpTransport transport) {
        io.jclaw.tools.mcp.McpTransport.ServerRequests handler = samplingFor.apply(server);
        if (handler != null) {
            // Installed before the handshake, because the handshake is what advertises it.
            transport.onServerRequest(handler);
        }
        return McpClient.connect(server, transport);
    }

    /**
     * A stable hash of everything about a server that could change its surface: how it is
     * reached, and which environment names it is given (never their values, which are not the
     * surface and do not belong in a cache key).
     */
    private static String fingerprint(McpServerStore.McpServer server) {
        String material = server.name() + '\u001f' + server.url() + '\u001f'
                + String.join("\u001e", server.command()) + '\u001f'
                + String.join("\u001e", new java.util.TreeSet<>(server.envSecrets().keySet())) + '\u001f'
                + server.authSecret();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static McpClient.McpTool toTool(Map<String, Object> row) {
        return new McpClient.McpTool(
                String.valueOf(row.get("name")),
                String.valueOf(row.getOrDefault("description", "")),
                row.get("inputSchema") instanceof Map<?, ?> schema ? (Map<String, Object>) schema : Map.of());
    }

    private static Map<String, Object> fromTool(McpClient.McpTool tool) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("name", tool.name());
        row.put("description", tool.description());
        row.put("inputSchema", tool.inputSchema());
        return row;
    }

    /** The capability an MCP server's bearer token must be bound to in the vault. */
    public static final io.jclaw.contracts.capability.CapabilityId CONNECT =
            io.jclaw.contracts.capability.CapabilityId.of("mcp.connect");

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
