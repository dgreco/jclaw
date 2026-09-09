package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.mcp.McpServerStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.domain.sandbox.SandboxSpec;
import io.jclaw.storage.jsonl.JsonlFile;
import io.jclaw.storage.mcp.JsonlMcpServerStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An MCP server started under the container backend goes through {@code docker run} with the
 * isolation flags, its configured environment reaches it without appearing on the command line,
 * and the protocol works through the container's stdio.
 *
 * <p>Docker itself is replaced by a script that records its argument vector and executes the
 * program it was asked to run, so the test proves what jclaw asks of Docker, not Docker.
 */
class McpSandboxTest {

    @TempDir Path dir;

    @Test
    @DisplayName("an MCP server runs in the sandbox with its environment passed by name")
    void serverRunsInContainer() throws IOException {
        Path argvLog = dir.resolve("docker-argv.log");
        Path fakeDocker = executable(dir.resolve("fake-docker"), "#!/bin/sh\n"
                + "for a in \"$@\"; do printf '%s\\n' \"$a\" >> '" + argvLog + "'; done\n"
                + "for last; do :; done\n"
                + "exec \"$last\"\n");
        Path fakeServer = executable(dir.resolve("fake-mcp"), "#!/bin/sh\n"
                + "while IFS= read -r line; do\n"
                + "  id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9]*\\).*/\\1/p')\n"
                + "  case \"$line\" in\n"
                + "    *'\"initialize\"'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"0\"}}}\\n' \"$id\";;\n"
                + "    *'\"tools/list\"'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"echoes\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}}\\n' \"$id\";;\n"
                + "    *'\"tools/call\"'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"key=%s\"}]}}\\n' \"$id\" \"$FAKE_KEY\";;\n"
                + "  esac\n"
                + "done\n");

        McpServerStore store = new JsonlMcpServerStore(new JsonlFile(dir.resolve("mcp.jsonl")));
        store.add(new McpServerStore.McpServer("fake", List.of(fakeServer.toString()),
                Map.of("FAKE_KEY", "s3cret-value"), true));
        SandboxSpec spec = SandboxSpec.defaults(fakeDocker.toString(), "node:22-alpine");

        McpRegistry registry = new McpRegistry(store, dir, Optional.of(spec));
        try {
            assertEquals(1, registry.handlers().size(), "the tool was discovered through the container's stdio");
            CapabilityHandler echo = registry.handlers().get(0);
            assertTrue(echo.descriptor().id().value().startsWith("mcp.fake."), echo.descriptor().id().value());

            var result = echo.execute(new CapabilityInvocation(echo.descriptor().id(), "c1", Map.of(),
                    TurnScope.local("p", new ThreadId("t")), new TurnRunId("run_1")), null);
            assertTrue(result.orElseThrow().contains("key=s3cret-value"),
                    "the configured environment reached the server: " + result);

            List<String> argv = Files.readAllLines(argvLog);
            assertTrue(argv.containsAll(List.of("run", "--rm", "--interactive", "--network", "none",
                    "--read-only", "node:22-alpine")), argv.toString());
            assertTrue(argv.contains("FAKE_KEY"), "the variable is passed by name for docker to read from its own environment");
            assertFalse(String.join("\n", argv).contains("s3cret-value"), "the value never appears in the argument vector");
            assertEquals(fakeServer.toString(), argv.get(argv.size() - 1), "the server program follows the image");
            assertFalse(argv.contains("/bin/sh"), "an MCP server is a program, not a shell command");
        } finally {
            registry.shutdown();
        }
    }

    private static Path executable(Path path, String content) throws IOException {
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }
}
