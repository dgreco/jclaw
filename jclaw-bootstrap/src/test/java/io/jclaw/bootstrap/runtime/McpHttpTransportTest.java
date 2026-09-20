// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.ports.capability.CapabilityHandler;
import io.jclaw.ports.capability.CapabilityId;
import io.jclaw.ports.capability.CapabilityInvocation;
import io.jclaw.ports.mcp.McpServerStore;
import io.jclaw.ports.secret.SecretVault;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnRunId;
import io.jclaw.ports.turn.TurnScope;
import io.jclaw.application.authority.guard.EgressGuard;
import io.jclaw.adapter.out.persistence.jsonl.JsonlFile;
import io.jclaw.adapter.out.persistence.mcp.JsonlMcpServerStore;
import io.jclaw.adapter.out.persistence.secret.FileSecretVault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote MCP server over streamable HTTP: the handshake, tools, resources, and prompts, with
 * the server answering in both shapes the transport must handle — a plain JSON body and an SSE
 * stream that sends progress notifications before the result.
 */
class McpHttpTransportTest {

    private static HttpServer server;
    private static final List<String> authorizations = new CopyOnWriteArrayList<>();
    private static final List<String> sessions = new CopyOnWriteArrayList<>();

    @TempDir Path dir;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            sessions.add(String.valueOf(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String id = body.replaceAll(".*\"id\":(\\d+).*", "$1");
            String method = body.replaceAll(".*\"method\":\"([^\"]+)\".*", "$1");

            if (!body.contains("\"id\"")) {
                exchange.sendResponseHeaders(202, -1); // a notification
                exchange.close();
                return;
            }
            String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2025-03-26\",\"serverInfo\":{\"name\":\"remote\",\"version\":\"1\"},"
                        + "\"capabilities\":{\"tools\":{},\"resources\":{},\"prompts\":{}}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"search\",\"description\":\"searches\","
                        + "\"inputSchema\":{\"type\":\"object\"}}]}";
                case "resources/list" -> "{\"resources\":[{\"uri\":\"doc://readme\",\"name\":\"README\","
                        + "\"description\":\"the readme\",\"mimeType\":\"text/markdown\"}]}";
                case "resources/read" -> "{\"contents\":[{\"uri\":\"doc://readme\",\"text\":\"resource body\"}]}";
                case "prompts/list" -> "{\"prompts\":[{\"name\":\"triage\",\"description\":\"triage a bug\","
                        + "\"arguments\":[{\"name\":\"issue\"}]}]}";
                case "prompts/get" -> "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"triage 42\"}}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"tool answered\"}]}";
                default -> null;
            };
            if (result == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";

            // The tool call answers as an event stream, preceded by a progress notification the
            // client must skip; everything else answers as plain JSON.
            if (method.equals("tools/call")) {
                byte[] sse = ("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
                        + "\"params\":{\"progress\":1}}\n\ndata: " + response + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, sse.length);
                exchange.getResponseBody().write(sse);
            } else {
                byte[] json = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (method.equals("initialize")) {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
                }
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private CapabilityInvocation call(CapabilityId id, Map<String, Object> arguments) {
        return new CapabilityInvocation(id, "c1", arguments, TurnScope.local("p", new ThreadId("t")),
                new TurnRunId("run_1"));
    }

    @Test
    @DisplayName("tools, resources, and prompts all work over HTTP, with the token from the vault")
    void remoteServer() {
        authorizations.clear();
        sessions.clear();

        byte[] key = new byte[32];
        FileSecretVault vault = new FileSecretVault(new JsonlFile(dir.resolve("secrets.jsonl")), key, Clock.systemUTC());
        vault.put(new SecretVault.SecretName("mcp-token"), "remote-token-123456",
                new SecretVault.Binding(McpRegistry.CONNECT, Set.of("127.0.0.1")));

        McpServerStore store = new JsonlMcpServerStore(new JsonlFile(dir.resolve("mcp.jsonl")));
        store.add(new McpServerStore.McpServer("remote", List.of(), Map.of(), endpoint(), "mcp-token", true));

        McpRegistry registry = new McpRegistry(store, dir, Optional.empty(), null,
                EgressGuard.allowingPrivateNetworks(), vault);
        try {
            Map<String, CapabilityHandler> byId = new TreeMap<>();
            registry.handlers().forEach(h -> byId.put(h.descriptor().id().value(), h));
            assertEquals(List.of("mcp.remote.get_prompt", "mcp.remote.list_prompts", "mcp.remote.list_resources",
                            "mcp.remote.read_resource", "mcp.remote.search"),
                    List.copyOf(byId.keySet()),
                    "a server that declares resources and prompts gets capabilities for them");

            assertEquals("tool answered", byId.get("mcp.remote.search")
                    .execute(call(CapabilityId.of("mcp.remote.search"), Map.of()), null).orElseThrow(),
                    "an SSE reply is read past its progress notification");
            assertTrue(byId.get("mcp.remote.list_resources")
                    .execute(call(CapabilityId.of("mcp.remote.list_resources"), Map.of()), null)
                    .orElseThrow().contains("doc://readme"));
            assertEquals("resource body", byId.get("mcp.remote.read_resource")
                    .execute(call(CapabilityId.of("mcp.remote.read_resource"), Map.of("uri", "doc://readme")), null)
                    .orElseThrow());
            assertTrue(byId.get("mcp.remote.list_prompts")
                    .execute(call(CapabilityId.of("mcp.remote.list_prompts"), Map.of()), null)
                    .orElseThrow().contains("triage(issue)"));
            assertEquals("user: triage 42", byId.get("mcp.remote.get_prompt")
                    .execute(call(CapabilityId.of("mcp.remote.get_prompt"),
                            Map.of("name", "triage", "arguments", Map.of("issue", "42"))), null)
                    .orElseThrow());

            assertTrue(authorizations.stream().allMatch(a -> a.equals("Bearer remote-token-123456")),
                    "every request carried the leased token: " + authorizations);
            assertTrue(sessions.contains("sess-1"), "the session id was echoed back: " + sessions);
            assertEquals("null", sessions.get(0), "the first request has no session yet");
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("a refused endpoint, an unknown secret, or a secret bound elsewhere all stop the connection")
    void refusals() {
        byte[] key = new byte[32];
        FileSecretVault vault = new FileSecretVault(new JsonlFile(dir.resolve("s2.jsonl")), key, Clock.systemUTC());
        vault.put(new SecretVault.SecretName("elsewhere"), "value-0123456789",
                new SecretVault.Binding(McpRegistry.CONNECT, Set.of("api.example.com")));

        // The default guard refuses loopback, exactly as it does for a tool's URL.
        McpServerStore blocked = new JsonlMcpServerStore(new JsonlFile(dir.resolve("m1.jsonl")));
        blocked.add(new McpServerStore.McpServer("blocked", List.of(), Map.of(), endpoint(), "", true));
        McpRegistry guarded = new McpRegistry(blocked, dir, Optional.empty(), null, EgressGuard.publicOnly(), vault);
        assertTrue(guarded.handlers().isEmpty(), "a private-network endpoint is not connected");
        guarded.shutdown();

        McpServerStore unknown = new JsonlMcpServerStore(new JsonlFile(dir.resolve("m2.jsonl")));
        unknown.add(new McpServerStore.McpServer("unknown", List.of(), Map.of(), endpoint(), "missing", true));
        McpRegistry noSecret = new McpRegistry(unknown, dir, Optional.empty(), null,
                EgressGuard.allowingPrivateNetworks(), vault);
        assertTrue(noSecret.handlers().isEmpty(), "an unknown auth secret is not guessed at");
        noSecret.shutdown();

        McpServerStore misbound = new JsonlMcpServerStore(new JsonlFile(dir.resolve("m3.jsonl")));
        misbound.add(new McpServerStore.McpServer("misbound", List.of(), Map.of(), endpoint(), "elsewhere", true));
        McpRegistry wrongHost = new McpRegistry(misbound, dir, Optional.empty(), null,
                EgressGuard.allowingPrivateNetworks(), vault);
        assertTrue(wrongHost.handlers().isEmpty(), "a secret bound to another host is not sent to this one");
        wrongHost.shutdown();

        assertFalse(authorizations.contains("Bearer value-0123456789"), "the misbound value never left the vault");
    }
}
