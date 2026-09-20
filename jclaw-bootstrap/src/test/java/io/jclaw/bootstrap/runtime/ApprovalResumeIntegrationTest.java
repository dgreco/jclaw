// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.capability.ApprovalStore;
import io.jclaw.ports.loop.CheckpointStore;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.turn.RunStore;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The approval lifecycle: park on a gate, decide, resume.
 *
 * <p>Runs under the default {@code interactive} policy, where {@code write_file} is above the
 * auto-approval ceiling and therefore gates. That is the whole point — this exercises the path a
 * real operator takes, not a relaxed one.
 */
@SpringBootTest
class ApprovalResumeIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-bootstraproval-it");
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
            return new MockModelProvider(List.of());
        }
    }

    @Autowired
    private JclawRuntime runtime;

    @Autowired
    private ModelProvider provider;

    @Autowired
    private JsonlApprovalStore approvals;

    @Autowired
    private CheckpointStore checkpoints;

    @Autowired
    private RunStore runs;

    private void script(Script... turns) {
        ((MockModelProvider) provider).reprogram(List.of(turns));
    }

    @Test
    @DisplayName("a write parks on a gate; approving and resuming completes it and writes the file")
    void approveThenResume() throws IOException {
        script(
                new Script.ToolCall("w1", "builtin.write_file",
                        Map.of("path", "approved.txt", "content", "written after approval")),
                new Script.Text("Done, the file is written."));

        // --- park ---
        JclawRuntime.TurnResult parked =
                runtime.submit(new ThreadId("gate-approve"), "write a file", new AtomicBoolean(false));

        assertEquals(TurnStatus.BLOCKED_APPROVAL, parked.status(), "a write should gate");
        assertFalse(Files.exists(workspace.resolve("approved.txt")),
                "nothing may be written before a human approves");

        // The run is recorded as resumable and has a checkpoint to resume from.
        assertTrue(runs.find(parked.run()).orElseThrow().isResumable());
        assertTrue(checkpoints.latestFor(parked.run()).isPresent(),
                "parking must leave a checkpoint or the run could never continue");

        List<ApprovalStore.Gate> pending = approvals.allPending();
        assertEquals(1, pending.size(), "exactly one gate should await a decision");
        ApprovalStore.Gate gate = pending.get(0);
        assertEquals("builtin.write_file", gate.capability().value());

        // --- decide and resume ---
        approvals.resolve(gate.id(), true);
        JclawRuntime.TurnResult resumed = runtime.resume(parked.run(), new AtomicBoolean(false));

        assertEquals(TurnStatus.COMPLETED, resumed.status());
        assertEquals("Done, the file is written.", resumed.reply().orElseThrow());
        assertEquals("written after approval",
                Files.readString(workspace.resolve("approved.txt")),
                "the approved effect should actually have happened");
    }

    @Test
    @DisplayName("denying resumes into a denial: the effect never happens and the model is told")
    void denyThenResume() {
        script(
                new Script.ToolCall("w2", "builtin.write_file",
                        Map.of("path", "denied.txt", "content", "should never exist")),
                new Script.Text("Understood, I could not write that."));

        JclawRuntime.TurnResult parked =
                runtime.submit(new ThreadId("gate-deny"), "write a file", new AtomicBoolean(false));
        assertEquals(TurnStatus.BLOCKED_APPROVAL, parked.status());

        ApprovalStore.Gate gate = approvals.allPending().stream()
                .filter(candidate -> candidate.run().equals(parked.run()))
                .findFirst()
                .orElseThrow();

        approvals.resolve(gate.id(), false);
        JclawRuntime.TurnResult resumed = runtime.resume(parked.run(), new AtomicBoolean(false));

        // The turn completes — a denial is information the model handles, not a crash.
        assertEquals(TurnStatus.COMPLETED, resumed.status());
        assertFalse(Files.exists(workspace.resolve("denied.txt")),
                "a denied capability must never take effect");
    }

    @Test
    @DisplayName("resume re-authorizes rather than assuming approval")
    void resumeWithoutDecisionParksAgain() {
        script(
                new Script.ToolCall("w3", "builtin.write_file",
                        Map.of("path", "undecided.txt", "content", "x")),
                new Script.Text("done"));

        JclawRuntime.TurnResult parked =
                runtime.submit(new ThreadId("gate-undecided"), "write a file", new AtomicBoolean(false));
        assertEquals(TurnStatus.BLOCKED_APPROVAL, parked.status());

        // Resume with the gate still undecided. If resume assumed authority, the file would appear.
        JclawRuntime.TurnResult resumed = runtime.resume(parked.run(), new AtomicBoolean(false));

        assertFalse(Files.exists(workspace.resolve("undecided.txt")),
                "resuming must not grant authority the human never gave");
        assertEquals(TurnStatus.BLOCKED_APPROVAL, resumed.status(),
                "an open question parks the run again");
        assertEquals(parked.gatePrompt(), resumed.gatePrompt(),
                "on the same gate: the human must not find a duplicate");
    }
}
