// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.capability.CapabilityHandler;
import io.jclaw.ports.capability.CapabilityId;
import io.jclaw.ports.capability.CapabilityInvocation;
import io.jclaw.ports.mcp.McpServerStore;
import io.jclaw.ports.secret.SecretVault;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnRunId;
import io.jclaw.ports.turn.TurnScope;
import io.jclaw.adapter.out.persistence.jsonl.JsonlFile;
import io.jclaw.adapter.out.persistence.mcp.JsonlMcpServerStore;
import io.jclaw.adapter.out.persistence.mcp.McpSurfaceCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server discovered once is published from cache and not started again until something calls
 * it, which is what stops every CLI invocation spawning every configured server.
 */
class McpLazyLifecycleTest {

    @TempDir Path dir;

    /** A server that appends a line every time it starts, so spawns can be counted. */
    private Path server(Path startLog) throws IOException {
        Path script = dir.resolve("fake-mcp");
        Files.writeString(script, "#!/bin/sh\n"
                + "printf 'start\\n' >> '" + startLog + "'\n"
                + "while IFS= read -r line; do\n"
                + "  id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9]*\\).*/\\1/p')\n"
                + "  case \"$line\" in\n"
                + "    *'\"initialize\"'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"f\",\"version\":\"0\"}}}\\n' \"$id\";;\n"
                + "    *'\"tools/list\"'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"tools\":[{\"name\":\"ping\",\"description\":\"pings\",\"inputSchema\":{\"type\":\"object\"}}]}}\\n' \"$id\";;\n"
                + "    *'\"tools/call\"'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}]}}\\n' \"$id\";;\n"
                + "  esac\n"
                + "done\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private int starts(Path startLog) throws IOException {
        return Files.exists(startLog) ? Files.readAllLines(startLog).size() : 0;
    }

    private McpRegistry registry(McpServerStore store, McpSurfaceCache cache) {
        return new McpRegistry(store, dir, Optional.empty(), null, null, SecretVault.empty(), cache);
    }

    @Test
    @DisplayName("the second start publishes the same surface from cache without spawning the server")
    void lazyAfterFirstDiscovery() throws IOException {
        Path startLog = dir.resolve("starts.log");
        McpServerStore store = new JsonlMcpServerStore(new JsonlFile(dir.resolve("mcp.jsonl")));
        store.add(new McpServerStore.McpServer("fake", List.of(server(startLog).toString()), Map.of(), true));
        McpSurfaceCache cache = new McpSurfaceCache(new JsonlFile(dir.resolve("surface.jsonl")), Clock.systemUTC());

        McpRegistry first = registry(store, cache);
        assertEquals(List.of("mcp.fake.ping"), first.handlers().stream()
                .map(h -> h.descriptor().id().value()).toList());
        assertEquals(1, starts(startLog), "the first start had to discover, so it spawned the server");
        first.shutdown();

        McpRegistry second = registry(store, cache);
        List<CapabilityHandler> handlers = second.handlers();
        assertEquals(List.of("mcp.fake.ping"), handlers.stream().map(h -> h.descriptor().id().value()).toList(),
                "the surface is published from cache");
        assertEquals("pings", handlers.get(0).descriptor().description().replaceAll(".*] ", ""));
        assertEquals(1, starts(startLog), "publishing the surface started nothing");

        // Invoking it is what starts the server.
        String answer = handlers.get(0).execute(new CapabilityInvocation(
                CapabilityId.of("mcp.fake.ping"), "c1", Map.of(),
                TurnScope.local("p", new ThreadId("t")), new TurnRunId("run_1")), null).orElseThrow();
        assertEquals("pong", answer);
        assertEquals(2, starts(startLog), "the call started the server, once");
        second.shutdown();
    }

    @Test
    @DisplayName("changing the configuration, or refreshing, invalidates the cache")
    void cacheIsFingerprinted() throws IOException {
        Path startLog = dir.resolve("starts2.log");
        Path script = server(startLog);
        McpSurfaceCache cache = new McpSurfaceCache(new JsonlFile(dir.resolve("surface2.jsonl")), Clock.systemUTC());

        McpServerStore store = new JsonlMcpServerStore(new JsonlFile(dir.resolve("mcp2.jsonl")));
        store.add(new McpServerStore.McpServer("fake", List.of(script.toString()), Map.of(), true));
        registry(store, cache).shutdown();
        assertEquals(1, starts(startLog));

        // Same name, a changed command: the cached surface no longer applies.
        McpServerStore changed = new JsonlMcpServerStore(new JsonlFile(dir.resolve("mcp3.jsonl")));
        changed.add(new McpServerStore.McpServer("fake", List.of(script.toString(), "--verbose"), Map.of(), true));
        registry(changed, cache).shutdown();
        assertEquals(2, starts(startLog), "a changed command is rediscovered");

        registry(store, cache).shutdown();
        assertEquals(3, starts(startLog), "and the change replaced the entry, so the old command is too");

        assertTrue(cache.drop("fake"));
        assertFalse(cache.drop("fake"), "dropping twice is not an error the second time");
        registry(store, cache).shutdown();
        assertEquals(4, starts(startLog), "refresh forces rediscovery");

        // Without a cache, every start discovers.
        registry(store, null).shutdown();
        assertEquals(5, starts(startLog));
    }
}
