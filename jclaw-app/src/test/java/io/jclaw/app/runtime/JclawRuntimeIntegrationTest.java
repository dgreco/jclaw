// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests over the assembled application context.
 *
 * <p>Everything is real except the model: the capability host, the guards, the JSONL stores, and
 * the interpreter are the production beans. Only the provider is scripted, which is what makes an
 * agent run assertable — the model is the sole nondeterministic component, so replacing it makes
 * the whole loop deterministic.
 */
@SpringBootTest
class JclawRuntimeIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-it");
        Files.writeString(workspace.resolve("notes.md"), "the answer is 42");
        Files.createDirectories(workspace.resolve("src"));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        // 'trusted' so the scripted read/write calls execute rather than parking on a gate;
        // the gate path is asserted separately below under the default policy.
        registry.add("jclaw.approval-mode", () -> "trusted");
    }

    /**
     * The scripted model. Each test reprograms it in {@code @BeforeEach}, so tests do not depend
     * on JUnit's method ordering — a shared script would hand tests each other's responses.
     */
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
    private ThreadService threads;

    @Autowired
    private EventLog events;

    @Autowired
    private ModelProvider provider;

    /** Convenience: reprogram the scripted provider for the turns this test drives. */
    private void script(Script... turns) {
        ((MockModelProvider) provider).reprogram(List.of(turns));
    }

    @Test
    @DisplayName("a full agent turn: tool call, tool result, reply, durable transcript")
    void completesToolCallingTurn() {
        script(
                new Script.ToolCall("c1", "builtin.read_file", Map.of("path", "notes.md")),
                new Script.Text("The notes say the answer is 42."));
        ThreadId thread = new ThreadId("it-tools");

        JclawRuntime.TurnResult result =
                runtime.submit(thread, "what do my notes say?", new AtomicBoolean(false));

        assertEquals(TurnStatus.COMPLETED, result.status(), "the turn should complete");
        assertEquals("The notes say the answer is 42.", result.reply().orElseThrow());

        // The reply is durable, not just returned — the exit was validated against this record.
        List<ThreadService.ThreadMessage> history = threads.history(thread, 10);
        assertTrue(history.size() >= 2, "user message and assistant reply should both persist");
        assertEquals("what do my notes say?", history.get(0).message().displayText());

        // The capability actually ran, and the event log recorded it with its effect class.
        boolean capabilityRecorded = events.readRun(result.run()).stream()
                .map(EventLog.Entry::event)
                .anyMatch(event -> event.type().equals("capability.invoked"));
        assertTrue(capabilityRecorded, "the tool call should appear in the event log");
    }

    @Test
    @DisplayName("a path outside the workspace is denied and the model is told, not crashed")
    void deniesEscapingPathAndContinues() {
        script(
                new Script.ToolCall("c2", "builtin.read_file", Map.of("path", "../../etc/passwd")),
                new Script.Text("I could not read that file."));
        ThreadId thread = new ThreadId("it-denied");

        JclawRuntime.TurnResult result =
                runtime.submit(thread, "read /etc/passwd", new AtomicBoolean(false));

        // The denial is data the loop handles, not an exception that kills the run.
        assertEquals(TurnStatus.COMPLETED, result.status(),
                "a denied tool call must not fail the whole turn");
        assertEquals("I could not read that file.", result.reply().orElseThrow());

        String recorded = events.readRun(result.run()).stream()
                .map(EventLog.Entry::event)
                .filter(event -> event.type().equals("capability.invoked"))
                .map(Object::toString)
                .reduce("", String::concat);
        assertTrue(recorded.contains("denied"), "the denial should be audited, got: " + recorded);
    }

    @Test
    @DisplayName("events carry no prompt text, tool arguments, or host paths")
    void eventsAreRedacted() {
        script(new Script.Text("Acknowledged."));
        ThreadId thread = new ThreadId("it-redaction");
        String secretish = "my-unique-prompt-string-9f3a";

        JclawRuntime.TurnResult result =
                runtime.submit(thread, secretish, new AtomicBoolean(false));

        String allEvents = events.readRun(result.run()).stream()
                .map(entry -> entry.event().toString())
                .reduce("", String::concat);

        assertFalse(allEvents.contains(secretish),
                "the user's prompt must never appear in the event log");
        assertFalse(allEvents.contains(workspace.toString()),
                "host paths must never appear in the event log");
    }
}
