// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.GateId;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.providers.mock.MockModelProvider.Script;
import io.jclaw.storage.approval.JsonlApprovalStore;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolving a gate where the user already is, rather than in a second shell.
 *
 * <p>One test method, because the scripted provider is a single sequence shared by the context
 * and splitting the phases would make them depend on the order JUnit happens to run them in.
 *
 * <p>Assertions are on what changed, not on what was printed: JLine writes terminal output
 * asynchronously, so reading it back is a race, and the decision and the resumed run are the
 * part that matters anyway.
 *
 * <p>None of this is about authority. The kernel re-authorizes on resume either way; answering
 * here only writes the decision the resumed run will read.
 */
@SpringBootTest
class ReplGateResolutionTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-repl-gate-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "interactive");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            // Exactly the calls the method below makes: a gated tool call per parked turn, and a
            // reply after each of the two resumes.
            return new MockModelProvider(List.of(
                    new Script.ToolCall("c1", "builtin.shell", Map.of("command", "pwd")),
                    new Script.ToolCall("c2", "builtin.shell", Map.of("command", "pwd")),
                    new Script.Text("finished after approving"),
                    new Script.ToolCall("c3", "builtin.shell", Map.of("command", "pwd")),
                    new Script.Text("finished after denying"),
                    new Script.ToolCall("c4", "builtin.shell", Map.of("command", "pwd"))));
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired JclawProperties properties;
    @Autowired JsonlApprovalStore approvals;
    @Autowired RunStore runs;
    @Autowired ThreadService threads;

    /** A terminal that is not a TTY, which is what a piped or scripted session gets. */
    private Terminal dumbTerminal() throws IOException {
        return TerminalBuilder.builder().type(Terminal.TYPE_DUMB)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build();
    }

    private ApprovalStore.Gate park(String thread) {
        JclawRuntime.TurnResult result = runtime.submit(new ThreadId(thread),
                ChatMessage.user("do the thing"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.BLOCKED_APPROVAL, result.status(), () -> String.valueOf(result.failureDetail()));
        return approvals.find(new GateId(result.gatePrompt().orElseThrow())).orElseThrow();
    }

    private String transcript(String thread) {
        return threads.history(new ThreadId(thread), Integer.MAX_VALUE).stream()
                .map(message -> message.message().displayText())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("a gate is answered in place, or left parked, and a piped session is never asked")
    void resolvesGatesInline() throws IOException {
        ReplCommand repl = new ReplCommand(runtime, properties, approvals);

        // A terminal nobody is watching is never asked a question, so scripted and piped
        // sessions behave exactly as they did: the command to run elsewhere is printed instead.
        ApprovalStore.Gate piped = park("piped");
        try (Terminal terminal = dumbTerminal()) {
            boolean asked = repl.report(terminal, repl.buildReader(terminal),
                    new JclawRuntime.TurnResult(piped.run(), TurnStatus.BLOCKED_APPROVAL, Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.of(piped.id().value()), 0, 0));
            assertFalse(asked, "a dumb terminal is told, not asked");
        }
        assertTrue(approvals.find(piped.id()).orElseThrow().isPending(), "nothing was decided");
        assertEquals(TurnStatus.BLOCKED_APPROVAL, runs.find(piped.run()).orElseThrow().status());

        // Yes resolves the gate and carries the turn through to its reply.
        ApprovalStore.Gate approved = park("approved");
        try (Terminal terminal = dumbTerminal()) {
            repl.applyDecision(terminal, repl.buildReader(terminal), approved, " Y ");
        }
        assertTrue(approvals.find(approved.id()).orElseThrow().isApproved());
        assertEquals(TurnStatus.COMPLETED, runs.find(approved.run()).orElseThrow().status());
        assertTrue(transcript("approved").contains("finished after approving"), transcript("approved"));

        // No resumes as well: the model is told, and the turn finishes without the effect.
        ApprovalStore.Gate denied = park("denied");
        try (Terminal terminal = dumbTerminal()) {
            repl.applyDecision(terminal, repl.buildReader(terminal), denied, "n");
        }
        assertFalse(approvals.find(denied.id()).orElseThrow().isApproved());
        assertEquals(TurnStatus.COMPLETED, runs.find(denied.run()).orElseThrow().status());
        assertTrue(transcript("denied").contains("finished after denying"), transcript("denied"));

        // Anything else leaves the question open. Silence is not consent.
        ApprovalStore.Gate parked = park("parked");
        try (Terminal terminal = dumbTerminal()) {
            LineReader reader = repl.buildReader(terminal);
            repl.applyDecision(terminal, reader, parked, "");
            repl.applyDecision(terminal, reader, parked, "later");
            repl.applyDecision(terminal, reader, parked, null);
        }
        assertTrue(approvals.find(parked.id()).orElseThrow().isPending(), "still an open question");
        assertEquals(TurnStatus.BLOCKED_APPROVAL, runs.find(parked.run()).orElseThrow().status());
    }
}
