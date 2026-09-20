// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.capability.ApprovalStore;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.turn.GateId;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnStatus;
import io.jclaw.adapter.out.model.mock.MockModelProvider;
import io.jclaw.adapter.out.model.mock.MockModelProvider.Script;
import io.jclaw.adapter.out.persistence.approval.JsonlApprovalStore;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate expiry: an unanswered gate lapses after the TTL; a resume then asks afresh rather than
 * parking on a stale question, and the lapsed gate cannot be answered any more.
 */
@SpringBootTest
class ApprovalExpiryIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-expiry-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-ttl", () -> "1s");
        // Pinned: a user-level jclaw.yaml with approval-mode=trusted would make the write run unattended.
        registry.add("jclaw.approval-mode", () -> "interactive");
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
    private JsonlApprovalStore gates;

    @Autowired
    private ModelProvider provider;

    @Test
    @DisplayName("an expired gate is replaced on resume, hidden from the list, and refuses a decision")
    void expiredGateIsAskedAfresh() throws InterruptedException {
        ((MockModelProvider) provider).reprogram(List.of(
                new Script.ToolCall("w1", "builtin.write_file", Map.of("path", "late.txt", "content", "x")),
                new Script.Text("written")));
        ThreadId thread = new ThreadId("expiry-it");

        JclawRuntime.TurnResult parked = runtime.submit(thread, "write late.txt", new AtomicBoolean(false));
        assertEquals(TurnStatus.BLOCKED_APPROVAL, parked.status());
        GateId first = new GateId(parked.gatePrompt().orElseThrow());
        assertTrue(gates.allPending().stream().anyMatch(gate -> gate.id().equals(first)));

        Thread.sleep(1_200);

        ApprovalStore.Gate stale = gates.find(first).orElseThrow();
        assertTrue(gates.expired(stale), "the TTL has passed");
        assertFalse(gates.allPending().stream().anyMatch(gate -> gate.id().equals(first)),
                "an expired gate is not listed as answerable");
        assertTrue(gates.allPending(true).stream().anyMatch(gate -> gate.id().equals(first)),
                "but --all still shows it");

        // Resume: the stale question is not answered, a fresh gate is raised.
        JclawRuntime.TurnResult again = runtime.resume(parked.run(), new AtomicBoolean(false));
        assertEquals(TurnStatus.BLOCKED_APPROVAL, again.status());
        GateId second = new GateId(again.gatePrompt().orElseThrow());
        assertNotEquals(first, second, "a resume after expiry asks afresh");
        assertFalse(Files.exists(workspace.resolve("late.txt")));

        // Approving the fresh gate grants exactly that invocation; the run then completes.
        gates.resolve(second, true);
        JclawRuntime.TurnResult done = runtime.resume(parked.run(), new AtomicBoolean(false));
        assertEquals(TurnStatus.COMPLETED, done.status());
        assertTrue(Files.exists(workspace.resolve("late.txt")));
    }
}
