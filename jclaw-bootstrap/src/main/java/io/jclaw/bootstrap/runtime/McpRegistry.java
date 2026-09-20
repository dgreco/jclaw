// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.bootstrap.mcp.McpOAuth;
import io.jclaw.ports.Result;
import io.jclaw.ports.capability.CapabilityHandler;
import io.jclaw.ports.capability.CapabilityId;
import io.jclaw.ports.capability.EffectClass;
import io.jclaw.ports.capability.TrustClass;
import io.jclaw.ports.extension.ExtensionRegistry;
import io.jclaw.ports.mcp.McpServerStore;
import io.jclaw.ports.secret.SecretVault;
import io.jclaw.domain.sandbox.SandboxSpec;
import io.jclaw.domain.secret.SecretInjection;
import io.jclaw.application.authority.guard.EgressGuard;
import io.jclaw.adapter.out.persistence.mcp.McpSurfaceCache;
import io.jclaw.adapter.out.capability.mcp.HttpTransport;
import io.jclaw.adapter.out.capability.mcp.McpCapabilityHandler;
import io.jclaw.adapter.out.capability.mcp.McpClient;
import io.jclaw.adapter.out.capability.mcp.McpSurfaceTools;
import io.jclaw.adapter.out.capability.mcp.McpTransport;
import io.jclaw.adapter.out.capability.mcp.StdioTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

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

    private static final Logger log = LoggerFactory.getLogger(McpRegistry.class);

    private final List<McpClient> clients = new ArrayList<>();
    private final List<CapabilityHandler> handlers = new ArrayList<>();
    private final EgressGuard egress;
    private final SecretVault vault;
    private final McpSurfaceCache cache;
    private final Clock clock = Clock.systemUTC();
    private Function<String, McpTransport.ServerRequests>
            samplingFor = server -> null;

    public McpRegistry(McpServerStore servers, Path workspaceRoot) {
        this(servers, workspaceRoot, Optional.empty());
    }

    /** @param sandbox when present, every server process runs inside this container contract */
    public McpRegistry(McpServerStore servers, Path workspaceRoot,
            Optional<SandboxSpec> sandbox) {
        this(servers, workspaceRoot, sandbox, null);
    }

    /**
     * @param extensions installed extensions; every enabled MCP extension is started beside the
     *                   servers from {@code mcp add}, and its tools carry the trust and effect the
     *                   installation earned
     */
    public McpRegistry(McpServerStore servers, Path workspaceRoot,
            Optional<SandboxSpec> sandbox,
            ExtensionRegistry extensions) {
        this(servers, workspaceRoot, sandbox, extensions, null,
                SecretVault.empty(), null);
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
            Optional<SandboxSpec> sandbox,
            ExtensionRegistry extensions,
            EgressGuard egress,
            SecretVault vault) {
        this(servers, workspaceRoot, sandbox, extensions, egress, vault, null);
    }

    /**
     * @param cache remembered surfaces; when a server's cached surface matches its current
     *              configuration, its capabilities are published from the cache and the server is
     *              not started until one of them is invoked. Null discovers everything eagerly
     */
    public McpRegistry(McpServerStore servers, Path workspaceRoot,
            Optional<SandboxSpec> sandbox,
            ExtensionRegistry extensions,
            EgressGuard egress,
            SecretVault vault,
            McpSurfaceCache cache) {
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
            bring(server, workspaceRoot, sandbox, Set.of(),
                    TrustClass.COMMUNITY, EffectClass.NETWORK);
        }
        if (extensions != null) {
            for (var installed : extensions.list()) {
                if (!installed.enabled() || installed.manifest().kind() != ExtensionRegistry.Kind.MCP) {
                    continue;
                }
                McpServerStore.McpServer server = new McpServerStore.McpServer(
                        installed.name(), installed.manifest().command(), installed.secrets(), true);
                // A verified package signed its host claim, so it is worth binding a secret to.
                bring(server, workspaceRoot, sandbox, Set.copyOf(installed.manifest().hosts()),
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
    private Result<McpTransport, String> transportFor(
            McpServerStore.McpServer server, Path workspaceRoot,
            Optional<SandboxSpec> sandbox,
            Set<String> declaredHosts) {

        if (!server.isHttp()) {
            // The store holds secret names; the values are leased here and live only in the
            // environment of the process about to start.
            return McpCredentials.resolve(server.envSecrets(), declaredHosts, vault)
                    .flatMap(environment -> StdioTransport.start(
                            server.command(), environment, workspaceRoot, sandbox));
        }
        if (egress == null) {
            return Result.err("http_transport_needs_an_egress_guard");
        }
        Result<URI, String> checked = egress.check(server.url());
        if (checked.isErr()) {
            return Result.err("endpoint_" + checked.errorAsOptional().orElse("denied"));
        }
        URI endpoint = checked.orElseThrow();

        // OAuth 2.1, when configured: a supplier rather than a value, because an access token
        // expires and the header has to be current when the request is built.
        if (server.oauth().isPresent()) {
            McpOAuth oauth = new McpOAuth(
                    server.oauth().get(), server.url(), vault, egress, clock);
            return Result.ok(
                    new HttpTransport(endpoint, oauth::header));
        }

        Optional<String> authorization;
        if (server.authSecret().isBlank()) {
            authorization = Optional.empty();
        } else {
            var name = new SecretVault.SecretName(server.authSecret());
            var lease = vault.lease(name);
            if (lease.isEmpty()) {
                return Result.err("auth_secret_unknown");
            }
            var refusal = SecretInjection.refuse(
                    lease.get().info().binding(), CONNECT,
                    Set.of(endpoint.getHost() == null ? "" : endpoint.getHost()));
            if (refusal.isPresent()) {
                return Result.err(refusal.get());
            }
            authorization = Optional.of("Bearer " + lease.get().value());
        }
        return Result.ok(new HttpTransport(endpoint, authorization));
    }

    /**
     * Registers one server, from the cache when it has an entry for this exact configuration and
     * from a live handshake otherwise. A live discovery updates the cache, so the next process
     * starts nothing.
     */
    private void bring(
            McpServerStore.McpServer server, Path workspaceRoot,
            Optional<SandboxSpec> sandbox,
            Set<String> declaredHosts,
            TrustClass trust, EffectClass effect) {

        String fingerprint = fingerprint(server);
        var cached = cache == null
                ? Optional.<McpSurfaceCache.Surface>empty()
                : cache.find(server.name(), fingerprint);
        if (cached.isPresent()) {
            McpClient client = McpClient.deferred(server.name(), cached.get().offers(),
                    () -> transportFor(server, workspaceRoot, sandbox, declaredHosts));
            withSampling(server.name(), client);
            clients.add(client);
            for (Map<String, Object> tool : cached.get().tools()) {
                handlers.add(new McpCapabilityHandler(client, toTool(tool), trust, effect));
            }
            handlers.addAll(McpSurfaceTools.handlersFor(client, trust, effect));
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
            Function<String, McpTransport.ServerRequests> factory) {
        this.samplingFor = Objects.requireNonNull(factory, "factory");
    }

    private void withSampling(String server, McpClient client) {
        McpTransport.ServerRequests handler = samplingFor.apply(server);
        if (handler != null) {
            client.withSampling(handler);
        }
    }

    private Result<McpClient, String> connectWithSampling(
            String server, McpTransport transport) {
        McpTransport.ServerRequests handler = samplingFor.apply(server);
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
                + String.join("\u001e", new TreeSet<>(server.envSecrets().keySet())) + '\u001f'
                + server.authSecret();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
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
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", tool.name());
        row.put("description", tool.description());
        row.put("inputSchema", tool.inputSchema());
        return row;
    }

    /** The capability an MCP server's bearer token must be bound to in the vault. */
    public static final CapabilityId CONNECT =
            CapabilityId.of("mcp.connect");

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
