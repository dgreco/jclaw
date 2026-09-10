// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.model.ModelProvider.ProviderFailure;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider refusing for want of credentials parks the run on an auth gate instead of failing
 * it; once the credential is there, resuming continues from the same point.
 */
@SpringBootTest
class AuthGateIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-auth-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
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
    @DisplayName("an AUTH failure parks the run; resume retries the model and completes")
    void authFailureParksThenResumes() {
        ((MockModelProvider) provider).reprogram(List.of(
                new Script.Failure(ProviderFailure.of(ProviderFailure.Kind.AUTH, "OPENAI_API_KEY is not set")),
                new Script.Text("credentials worked")));
        ThreadId thread = new ThreadId("auth-it");

        JclawRuntime.TurnResult parked = runtime.submit(thread, "hello", new AtomicBoolean(false));

        assertEquals(TurnStatus.BLOCKED_AUTH, parked.status(), "not a failure: a park");
        String gateId = parked.gatePrompt().orElseThrow();
        ApprovalStore.Gate gate = gates.find(new io.jclaw.contracts.turn.GateId(gateId)).orElseThrow();
        assertEquals(GateKind.AUTH, gate.kind());
        assertTrue(gate.isPending());
        assertEquals("model.mock", gate.capability().value());
        assertTrue(gate.prompt().contains("OPENAI_API_KEY"), "the human is told what is missing");
        assertTrue(gates.allPending().stream().anyMatch(ApprovalStore.Gate::isAuth),
                "the gate is listed for 'jclaw approvals list'");

        // The credential "appears" (the mock now answers); no decision is needed to resume.
        JclawRuntime.TurnResult resumed = runtime.resume(parked.run(), new AtomicBoolean(false));

        assertEquals(TurnStatus.COMPLETED, resumed.status());
        assertEquals("credentials worked", resumed.reply().orElseThrow());
    }

    @Test
    @DisplayName("resuming without the credential parks again on a fresh gate")
    void stillMissingParksAgain() {
        ((MockModelProvider) provider).reprogram(List.of(
                new Script.Failure(ProviderFailure.of(ProviderFailure.Kind.AUTH, "credentials rejected")),
                new Script.Failure(ProviderFailure.of(ProviderFailure.Kind.AUTH, "credentials rejected"))));

        JclawRuntime.TurnResult first = runtime.submit(new ThreadId("auth-again"), "hi", new AtomicBoolean(false));
        JclawRuntime.TurnResult second = runtime.resume(first.run(), new AtomicBoolean(false));

        assertEquals(TurnStatus.BLOCKED_AUTH, first.status());
        assertEquals(TurnStatus.BLOCKED_AUTH, second.status());
        assertTrue(!first.gatePrompt().equals(second.gatePrompt()), "each attempt raises its own gate");
    }
}
