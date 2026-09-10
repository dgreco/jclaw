// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.providers.mock.MockModelProvider.Script;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sandbox lane, driven through a fake {@code docker} binary that records its argument vector
 * and runs the command locally. What is verified is the contract the lane hands to the
 * container runtime: the isolation flags, the mount, the working directory, and that the command
 * and its output pass through unchanged. A real daemon is not needed for that, and the CI
 * runner has none.
 */
@SpringBootTest
class SandboxIntegrationTest {

    private static Path workspace;
    private static Path fakeDocker;
    private static Path argvLog;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-sandbox-it");
        Files.writeString(workspace.resolve("hello.txt"), "hello from the workspace");
        argvLog = workspace.resolve("docker-argv.log");
        fakeDocker = workspace.resolve("fake-docker");
        // Logs every argument on its own line, then runs the last argument as the command.
        Files.writeString(fakeDocker, "#!/bin/sh\n"
                + "for a in \"$@\"; do printf '%s\\n' \"$a\" >> '" + argvLog + "'; done\n"
                + "for last; do :; done\n"
                + "cd '" + workspace + "' && /bin/sh -c \"$last\"\n");
        Files.setPosixFilePermissions(fakeDocker, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.shell-backend", () -> "docker");
        registry.add("jclaw.sandbox-docker", () -> fakeDocker.toString());
        registry.add("jclaw.sandbox-image", () -> "alpine:3.20");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of());
        }
    }

    @Autowired
    private JclawRuntime runtime;

    @Autowired
    private ModelProvider provider;

    @Test
    @DisplayName("a shell command goes through docker run with the isolation flags, and its output comes back")
    void shellRunsInTheSandbox() throws IOException {
        MockModelProvider mock = (MockModelProvider) provider;
        mock.reprogram(List.of(
                new Script.ToolCall("sh1", "builtin.shell", Map.of("command", "cat hello.txt")),
                new Script.Text("done")));

        JclawRuntime.TurnResult result =
                runtime.submit(new ThreadId("sandbox-it"), "read hello", new AtomicBoolean(false));

        assertEquals(TurnStatus.COMPLETED, result.status());
        String toolResult = mock.lastRequest().orElseThrow().messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .flatMap(m -> m.content().stream())
                .filter(ContentBlock.ToolResult.class::isInstance)
                .map(b -> ((ContentBlock.ToolResult) b).content())
                .findFirst().orElseThrow();
        assertTrue(toolResult.contains("hello from the workspace"), toolResult);

        List<String> argv = Files.readAllLines(argvLog);
        assertEquals("run", argv.get(0));
        assertTrue(argv.containsAll(List.of("--rm", "--network", "none", "--read-only", "--pids-limit")));
        assertTrue(argv.contains(workspace.toRealPath() + ":/workspace:rw"),
                "only the workspace is mounted, by its real path: " + argv);
        assertTrue(argv.contains("alpine:3.20"));
        assertEquals("cat hello.txt", argv.get(argv.size() - 1), "the command is passed through verbatim");
    }
}
