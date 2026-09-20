// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.capability.CapabilityId;
import io.jclaw.ports.event.EventLog;
import io.jclaw.ports.event.JclawEvent;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.secret.SecretVault;
import io.jclaw.ports.secret.SecretVault.Binding;
import io.jclaw.ports.secret.SecretVault.SecretName;
import io.jclaw.ports.thread.ThreadService;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnStatus;
import io.jclaw.domain.secret.SecretStaging;
import io.jclaw.adapter.out.model.mock.MockModelProvider;
import io.jclaw.adapter.out.model.mock.MockModelProvider.Script;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A credential reaches a child process's environment and never its command line.
 *
 * <p>The shell command prints the variable, which proves the value arrived; everything else here
 * is about where it did <em>not</em> arrive — the arguments the model wrote, the checkpoints, the
 * transcript, and the stored result all keep the secret's name, and the value is masked wherever
 * the command echoed it.
 */
@SpringBootTest
class SecretStagingIntegrationTest {

    private static final String VALUE = "ghp-staged-value-0123456789";

    private static Path workspace;

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-staging-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of(
                    // Staged: the value goes into the environment, the name into the arguments.
                    new Script.ToolCall("s1", "builtin.shell", Map.of(
                            "command", "printf '%s' \"$GH_TOKEN\"",
                            SecretStaging.ARGUMENT, Map.of("GH_TOKEN", "gh"))),
                    new Script.Text("staged ok"),
                    // The same secret asked for the other way: substituted into an argument.
                    new Script.ToolCall("s2", "builtin.shell", Map.of(
                            "command", "echo {{secret:gh}}")),
                    new Script.Text("substitution refused"),
                    // A secret bound to a host, asked for as a staged variable.
                    new Script.ToolCall("s3", "builtin.shell", Map.of(
                            "command", "printf '%s' \"$API\"",
                            SecretStaging.ARGUMENT, Map.of("API", "hosted"))),
                    new Script.Text("host-bound refused")));
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired SecretVault vault;
    @Autowired EventLog events;
    @Autowired ThreadService threads;

    @Test
    @DisplayName("a staged secret reaches the environment; the same secret cannot be substituted")
    void stagedNotSubstituted() throws IOException {
        vault.put(new SecretName("gh"), VALUE,
                new Binding(CapabilityId.of("builtin.shell"), Set.of(Binding.SUBPROCESS)));
        vault.put(new SecretName("hosted"), "hosted-value-0123456789",
                new Binding(CapabilityId.of("builtin.shell"), Set.of("api.github.com")));

        ThreadId thread = new ThreadId("staging");
        JclawRuntime.TurnResult staged = runtime.submit(thread,
                ChatMessage.user("read the token"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, staged.status(), () -> String.valueOf(staged.failureDetail()));
        List<JclawEvent> stagedEvents = events.readRun(staged.run()).stream().map(EventLog.Entry::event).toList();
        assertTrue(stagedEvents.stream().anyMatch(e -> e instanceof JclawEvent.SecretInjected s
                && s.secret().equals("gh")), "the audit log names the staged secret");
        assertTrue(stagedEvents.stream().anyMatch(e -> e instanceof JclawEvent.CapabilityInvoked c
                && c.outcome().equals("ok")), "the command ran");

        JclawRuntime.TurnResult substituted = runtime.submit(thread,
                ChatMessage.user("now echo it"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, substituted.status());
        assertTrue(events.readRun(substituted.run()).stream().map(EventLog.Entry::event)
                        .anyMatch(e -> e instanceof JclawEvent.CapabilityInvoked c && c.outcome().equals("denied")),
                "a subprocess-bound secret has no host, so it can never be put in an argument");

        JclawRuntime.TurnResult hostBound = runtime.submit(thread,
                ChatMessage.user("try the hosted one"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, hostBound.status());
        assertTrue(events.readRun(hostBound.run()).stream().map(EventLog.Entry::event)
                        .anyMatch(e -> e instanceof JclawEvent.CapabilityInvoked c && c.outcome().equals("denied")),
                "a host-bound secret must not be handed to a process that could send it anywhere");

        // Nothing durable holds the value: the arguments carry the secret's name, and the
        // command's own echo of it is masked by the redactor before it is stored. Checkpoint
        // payloads are base64, so they are decoded before the scan — otherwise "the value is not
        // on disk" would be a claim about an encoding rather than about the contents.
        boolean name = false;
        try (var files = Files.walk(workspace.resolve(".state"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = decodeEmbeddedPayloads(Files.readString(file));
                assertFalse(content.contains(VALUE), file + " holds the staged value");
                name |= content.contains("GH_TOKEN") && content.contains("secret_env");
            }
        }
        assertTrue(name, "the state keeps the variable-to-name request the model wrote");
        assertFalse(threads.history(thread, Integer.MAX_VALUE).toString().contains(VALUE));
    }

    /** Appends the decoded form of every base64 {@code "payload"} field to the text. */
    private static String decodeEmbeddedPayloads(String content) {
        StringBuilder all = new StringBuilder(content);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"payload\":\"([A-Za-z0-9+/=]+)\"").matcher(content);
        while (matcher.find()) {
            try {
                all.append('\n').append(new String(
                        java.util.Base64.getDecoder().decode(matcher.group(1)),
                        java.nio.charset.StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Not base64 after all; the raw form is already in the buffer.
            }
        }
        return all.toString();
    }
}
