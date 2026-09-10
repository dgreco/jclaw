// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.providers.mock.MockModelProvider.Script;
import io.jclaw.storage.approval.JsonlApprovalStore;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asynchronous subagents: the parent parks on a process gate while the child runs under the
 * scheduler, and is requeued when the child finishes.
 */
@SpringBootTest
class AsyncSubagentIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-async-sub-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.subagents-async", () -> "true");
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
    private TurnRunScheduler scheduler;

    @Autowired
    private RunStore runs;

    @Autowired
    private JsonlApprovalStore gates;

    @Autowired
    private ModelProvider provider;

    @Test
    @DisplayName("the parent parks WAITING_PROCESS, the child runs, the parent resumes with its reply")
    void parentParksWhileChildRuns() {
        MockModelProvider mock = (MockModelProvider) provider;
        // Parent asks for a subagent; the child replies; then the parent, resumed, replies.
        mock.reprogram(List.of(
                new Script.ToolCall("s1", "builtin.spawn_subagent",
                        Map.of("description", "count", "prompt", "count the files")),
                new Script.Text("child: three files"),
                new Script.Text("parent: the child counted three files")));

        JclawRuntime.TurnResult parked =
                runtime.submit(new ThreadId("async-parent"), "delegate the count", new AtomicBoolean(false));

        assertEquals(TurnStatus.WAITING_PROCESS, parked.status(), "the parent waits, it does not block");
        ApprovalStore.Gate gate = gates.find(new io.jclaw.contracts.turn.GateId(parked.gatePrompt().orElseThrow()))
                .orElseThrow();
        assertEquals(GateKind.PROCESS, gate.kind());
        assertTrue(gate.prompt().contains("run_"), "the gate names the child run");

        // The child is queued on a derived thread; one scheduler pass runs it.
        List<TurnRunScheduler.Outcome> first = scheduler.runOnce(2, new AtomicBoolean(false));
        assertEquals(1, first.size());
        assertEquals("child: three files", first.get(0).result().reply().orElseThrow());
        assertTrue(first.get(0).run().value().startsWith("run_"));

        // Reaping the child requeued the parent; the next pass resumes it.
        assertEquals(TurnStatus.QUEUED, runs.find(parked.run()).orElseThrow().status(),
                "the finished child requeues its waiting parent");
        List<TurnRunScheduler.Outcome> second = scheduler.runOnce(2, new AtomicBoolean(false));
        assertEquals(1, second.size());
        assertEquals(parked.run(), second.get(0).run());
        assertEquals(TurnStatus.COMPLETED, second.get(0).result().status());
        assertEquals("parent: the child counted three files", second.get(0).result().reply().orElseThrow());

        // The parent's request carried the child's conclusion as the tool result.
        String toolResult = mock.lastRequest().orElseThrow().messages().stream()
                .filter(m -> m.role() == io.jclaw.contracts.model.ChatMessage.Role.TOOL)
                .flatMap(m -> m.content().stream())
                .map(Object::toString)
                .reduce("", String::concat);
        assertTrue(toolResult.contains("child: three files"), toolResult);
    }
}
