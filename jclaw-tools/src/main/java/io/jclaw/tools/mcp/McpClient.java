// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.tools.mcp;

import io.jclaw.contracts.Result;
import io.jclaw.domain.sandbox.SandboxSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A connected MCP server: the protocol, over whichever {@link McpTransport} carries it.
 *
 * <p>Speaks the parts of MCP jclaw actually uses — the handshake, tools, resources, and prompts —
 * and no more. Notifications the server sends are ignored rather than dispatched: this client
 * asks questions, and a server that pushes an unsolicited instruction is not a server whose
 * instruction should reach the loop.
 *
 * <p>What the server may offer is read from its handshake, not assumed: {@link #offers} reports
 * the declared capabilities, so a server without resources is never asked for them, and
 * capabilities are registered only for what it said it has.
 */
public final class McpClient implements AutoCloseable {

    /**
     * The version this client speaks. {@code 2025-03-26} is the revision that introduced the
     * streamable HTTP transport; servers on the older revision negotiate down in their reply,
     * and nothing here depends on the difference.
     */
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final AtomicLong nextId = new AtomicLong(1);
    private final String serverName;
    private final Supplier<Result<McpTransport, String>> connector;
    private final Object connectLock = new Object();
    private volatile McpTransport transport;
    /** What the server said it offers: some of {@code tools}, {@code resources}, {@code prompts}. */
    private volatile Set<String> offers;
    private volatile McpTransport.ServerRequests sampling;

    private McpClient(
            String serverName,
            Supplier<Result<McpTransport, String>> connector,
            McpTransport transport,
            Set<String> offers) {
        this.serverName = serverName;
        this.connector = connector;
        this.transport = transport;
        this.offers = Set.copyOf(offers);
    }

    /** A tool the server advertises. */
    public record McpTool(String name, String description, Map<String, Object> inputSchema) {
        public McpTool {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
        }
    }

    /** A resource the server can read: addressable content, not an action. */
    public record McpResource(String uri, String name, String description, String mimeType) {
        public McpResource {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(mimeType, "mimeType");
        }
    }

    /** A prompt template the server offers, with the arguments it takes. */
    public record McpPrompt(String name, String description, List<String> arguments) {
        public McpPrompt {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    /** Starts a server as a child process over stdio. */
    public static Result<McpClient, String> start(
            String serverName, List<String> command, Map<String, String> extraEnv, Path workingDirectory) {
        return start(serverName, command, extraEnv, workingDirectory, Optional.empty());
    }

    /** As {@link #start(String, List, Map, Path)}, optionally inside a container. */
    public static Result<McpClient, String> start(
            String serverName, List<String> command, Map<String, String> extraEnv, Path workingDirectory,
            Optional<SandboxSpec> sandbox) {
        return StdioTransport.start(command, extraEnv, workingDirectory, sandbox)
                .flatMap(transport -> connect(serverName, transport));
    }

    /**
     * Completes the handshake over an already-built transport.
     *
     * <p>The one entry point for a remote server: the caller validates the URL against the egress
     * guard and leases any credential, then hands over a ready transport.
     */
    public static Result<McpClient, String> connect(String serverName, McpTransport transport) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(transport, "transport");
        McpClient client = new McpClient(serverName, () -> Result.ok(transport), null, Set.of());
        Result<McpTransport, String> ready = client.ensureConnected();
        return ready.isErr()
                ? Result.err(ready.errorAsOptional().orElse("handshake_failed"))
                : Result.ok(client);
    }

    /**
     * A client that has not connected yet and will on its first call.
     *
     * <p>How a server stays unstarted until something actually uses it. The surface is supplied
     * from a cache of an earlier discovery, so the capabilities can be published — and the whole
     * prompt built — without a process, a socket, or a handshake. A CLI invocation that never
     * calls an MCP tool therefore never starts one.
     *
     * @param offers    what the server declared last time it was asked
     * @param connector opens the transport, when the moment comes
     */
    public static McpClient deferred(
            String serverName, Set<String> offers,
            Supplier<Result<McpTransport, String>> connector) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(offers, "offers");
        Objects.requireNonNull(connector, "connector");
        return new McpClient(serverName, connector, null, offers);
    }

    /** Whether this client has actually opened its transport. */
    public boolean isConnected() {
        return transport != null;
    }

    /**
     * Opens the transport and completes the handshake, once.
     *
     * <p>The handshake writes directly to the transport rather than going through
     * {@link #request}, which would come back here and recurse.
     */
    private Result<McpTransport, String> ensureConnected() {
        McpTransport current = transport;
        if (current != null) {
            return Result.ok(current);
        }
        synchronized (connectLock) {
            if (transport != null) {
                return Result.ok(transport);
            }
            Result<McpTransport, String> opened = connector.get();
            if (opened.isErr()) {
                return opened;
            }
            McpTransport fresh = opened.orElseThrow();

            long id = nextId.getAndIncrement();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", id);
            envelope.put("method", "initialize");
            // Declare sampling only when a handler is installed. Advertising a capability the
            // client would then refuse is worse than not advertising it: a server would build a
            // plan around it and fail late.
            Map<String, Object> clientCapabilities = sampling == null
                    ? Map.of() : Map.of("sampling", Map.of());
            envelope.put("params", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", clientCapabilities,
                    "clientInfo", Map.of("name", "jclaw", "version", "0.1.0")));
            if (sampling != null) {
                fresh.onServerRequest(sampling);
            }
            Result<Map<String, Object>, String> handshake =
                    fresh.send(envelope, Optional.of(id), REQUEST_TIMEOUT);
            if (handshake.isErr()) {
                fresh.close();
                return Result.err(handshake.errorAsOptional().orElse("handshake_failed"));
            }
            // MCP requires this notification before any other call.
            Map<String, Object> initialized = new LinkedHashMap<>();
            initialized.put("jsonrpc", "2.0");
            initialized.put("method", "notifications/initialized");
            initialized.put("params", Map.of());
            fresh.send(initialized, Optional.empty(), REQUEST_TIMEOUT);

            offers = declaredCapabilities(handshake.orElseThrow());
            transport = fresh;
            return Result.ok(fresh);
        }
    }

    private static Set<String> declaredCapabilities(Map<String, Object> handshake) {
        if (!(handshake.get("capabilities") instanceof Map<?, ?> capabilities)) {
            return Set.of();
        }
        Set<String> declared = new LinkedHashSet<>();
        capabilities.keySet().forEach(key -> declared.add(String.valueOf(key)));
        return declared;
    }

    /**
     * Answers the server's {@code sampling/createMessage} requests, when the host allows them.
     *
     * <p>Set before connecting. Installing one is what makes the client advertise the capability
     * at all, so a host that has not opted into sampling never invites a server to ask.
     */
    public void withSampling(McpTransport.ServerRequests handler) {
        this.sampling = Objects.requireNonNull(handler, "handler");
    }

    public Set<String> offers() {
        return offers;
    }

    /** Whether the server declared a capability. A server that declared nothing is asked for tools anyway. */
    public boolean offersOrUnknown(String capability) {
        return offers.isEmpty() || offers.contains(capability);
    }

    @SuppressWarnings("unchecked")
    public Result<List<McpTool>, String> listTools() {
        return request("tools/list", Map.of()).flatMap(result -> {
            if (!(result.get("tools") instanceof List<?> list)) {
                return Result.err("malformed_tools_list");
            }
            List<McpTool> tools = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> tool = (Map<String, Object>) map;
                    tools.add(new McpTool(
                            String.valueOf(tool.get("name")),
                            String.valueOf(tool.getOrDefault("description", "")),
                            tool.get("inputSchema") instanceof Map<?, ?> schema
                                    ? (Map<String, Object>) schema
                                    : Map.of()));
                }
            }
            return Result.ok(List.copyOf(tools));
        });
    }

    public Result<String, String> callTool(String name, Map<String, Object> arguments) {
        return request("tools/call", Map.of("name", name, "arguments", arguments))
                .flatMap(McpClient::flattenContent);
    }

    @SuppressWarnings("unchecked")
    public Result<List<McpResource>, String> listResources() {
        return request("resources/list", Map.of()).flatMap(result -> {
            if (!(result.get("resources") instanceof List<?> list)) {
                return Result.err("malformed_resources_list");
            }
            List<McpResource> resources = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> resource = (Map<String, Object>) map;
                    resources.add(new McpResource(
                            String.valueOf(resource.get("uri")),
                            String.valueOf(resource.getOrDefault("name", resource.get("uri"))),
                            String.valueOf(resource.getOrDefault("description", "")),
                            String.valueOf(resource.getOrDefault("mimeType", "text/plain"))));
                }
            }
            return Result.ok(List.copyOf(resources));
        });
    }

    /** Reads a resource, flattened to text the way tool content is. */
    @SuppressWarnings("unchecked")
    public Result<String, String> readResource(String uri) {
        return request("resources/read", Map.of("uri", uri)).flatMap(result -> {
            if (!(result.get("contents") instanceof List<?> contents)) {
                return Result.err("malformed_resource_contents");
            }
            StringBuilder text = new StringBuilder();
            for (Object element : contents) {
                if (!(element instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> content = (Map<String, Object>) map;
                if (content.get("text") instanceof String value) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(value);
                } else if (content.containsKey("blob")) {
                    // Binary content would be base64 the model cannot use; name it instead.
                    text.append("\n[binary resource ").append(content.getOrDefault("mimeType", "?"))
                            .append(" omitted]");
                }
            }
            return Result.ok(text.isEmpty() ? "(no readable content)" : text.toString());
        });
    }

    @SuppressWarnings("unchecked")
    public Result<List<McpPrompt>, String> listPrompts() {
        return request("prompts/list", Map.of()).flatMap(result -> {
            if (!(result.get("prompts") instanceof List<?> list)) {
                return Result.err("malformed_prompts_list");
            }
            List<McpPrompt> prompts = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> prompt = (Map<String, Object>) map;
                    List<String> arguments = new ArrayList<>();
                    if (prompt.get("arguments") instanceof List<?> declared) {
                        for (Object argument : declared) {
                            if (argument instanceof Map<?, ?> argumentMap) {
                                arguments.add(String.valueOf(argumentMap.get("name")));
                            }
                        }
                    }
                    prompts.add(new McpPrompt(
                            String.valueOf(prompt.get("name")),
                            String.valueOf(prompt.getOrDefault("description", "")),
                            arguments));
                }
            }
            return Result.ok(List.copyOf(prompts));
        });
    }

    /** Expands a prompt template to its message text. */
    @SuppressWarnings("unchecked")
    public Result<String, String> getPrompt(String name, Map<String, Object> arguments) {
        return request("prompts/get", Map.of("name", name, "arguments", arguments)).flatMap(result -> {
            if (!(result.get("messages") instanceof List<?> messages)) {
                return Result.err("malformed_prompt_messages");
            }
            StringBuilder text = new StringBuilder();
            for (Object element : messages) {
                if (!(element instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> message = (Map<String, Object>) map;
                String role = String.valueOf(message.getOrDefault("role", "user"));
                String body = message.get("content") instanceof Map<?, ?> content
                        ? String.valueOf(((Map<String, Object>) content).getOrDefault("text", ""))
                        : "";
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(role).append(": ").append(body);
            }
            return Result.ok(text.isEmpty() ? "(empty prompt)" : text.toString());
        });
    }

    @SuppressWarnings("unchecked")
    private static Result<String, String> flattenContent(Map<String, Object> result) {
        if (result.get("isError") instanceof Boolean error && error) {
            return Result.err("tool_reported_error");
        }
        if (!(result.get("content") instanceof List<?> blocks)) {
            return Result.ok("(no content)");
        }
        StringBuilder text = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map) {
                Map<String, Object> typed = (Map<String, Object>) map;
                String kind = String.valueOf(typed.get("type"));
                if ("text".equals(kind)) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(String.valueOf(typed.get("text")));
                } else {
                    text.append("\n[").append(kind).append(" content omitted]");
                }
            }
        }
        return Result.ok(text.isEmpty() ? "(no text content)" : text.toString());
    }

    public String serverName() {
        return serverName;
    }

    /** A client that has not connected is alive: it still can. */
    public boolean isAlive() {
        McpTransport current = transport;
        return current == null || current.isAlive();
    }

    /** How this server is reached, for logs and {@code mcp list}. Never a credential. */
    public String transportDescription() {
        McpTransport current = transport;
        return current == null ? "not connected" : current.describe();
    }

    private Result<Map<String, Object>, String> request(String method, Map<String, Object> params) {
        Result<McpTransport, String> ready = ensureConnected();
        if (ready.isErr()) {
            return Result.err(ready.errorAsOptional().orElse("server_unavailable"));
        }
        long id = nextId.getAndIncrement();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.put("params", params);
        return ready.orElseThrow().send(envelope, Optional.of(id), REQUEST_TIMEOUT);
    }

    @Override
    public void close() {
        McpTransport current = transport;
        if (current != null) {
            current.close();
        }
    }
}
