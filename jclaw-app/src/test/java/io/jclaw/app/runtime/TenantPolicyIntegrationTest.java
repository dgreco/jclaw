package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.kernel.capability.CapabilityPolicyResolver;
import io.jclaw.providers.mock.MockModelProvider;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One host, tenants that are not equal: a named agent decides what a tenant's runs are, a tenant
 * policy decides what they may do, and a budget decides when they stop.
 */
@SpringBootTest
class TenantPolicyIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-tenant-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.agents.reviewer.model", () -> "review-model");
        registry.add("jclaw.agents.reviewer.system-prompt", () -> "You review carefully.");
        registry.add("jclaw.tenant-policies.alice.agent", () -> "reviewer");
        registry.add("jclaw.tenant-policies.alice.approval-mode", () -> "read-only");
        registry.add("jclaw.tenant-policies.alice.denied-capabilities", () -> "builtin.http_fetch");
        // One token is less than any real turn costs, so the second admission is refused.
        registry.add("jclaw.tenant-token-budget", () -> "1");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("done");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired RunStore runs;
    @Autowired MockModelProvider provider;
    @Autowired CapabilityPolicyResolver policies;
    @Autowired TenantLedger ledger;

    @Test
    @DisplayName("a tenant's agent picks the profile, its policy narrows the ceiling, and its budget stops it")
    void tenantsDifferFromOneAnother() {
        // The agent is part of the scope, so the run records which configuration produced it.
        var scope = runtime.scopeFor("alice", new ThreadId("work"));
        assertEquals("reviewer", scope.agent());
        assertEquals("default", runtime.scopeFor("bob", new ThreadId("work")).agent());
        assertEquals("default", runtime.scopeFor(new ThreadId("work")).agent(), "the CLI is unaffected");

        JclawRuntime.TurnResult first = runtime.submit("alice", new ThreadId("work"),
                ChatMessage.user("review this"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, first.status(), () -> String.valueOf(first.failureDetail()));
        assertEquals("review-model", runs.find(first.run()).orElseThrow().model(),
                "the agent's model, not the host's");
        assertTrue(provider.lastRequest().orElseThrow().system().startsWith("You review carefully."),
                "and the agent's prompt");

        // A tenant policy narrows: alice is read-only and denied a capability the host allows.
        var alicePolicy = policies.forScope(scope);
        var hostPolicy = policies.forScope(runtime.scopeFor(new ThreadId("work")));
        assertTrue(hostPolicy.autoApproveCeiling().compareTo(alicePolicy.autoApproveCeiling()) > 0,
                "the host runs trusted; alice does not");
        assertTrue(alicePolicy.isDenied(CapabilityId.of("builtin.http_fetch")));
        assertFalse(hostPolicy.isDenied(CapabilityId.of("builtin.http_fetch")),
                "a tenant's denial is that tenant's, not everyone's");
        assertEquals(EffectClass.PURE, io.jclaw.contracts.capability.TrustClass.COMMUNITY.autoApprovalCeiling(),
                "third-party trust ceilings are untouched by any of this");

        // The budget is spent by the first run, so the next admission is refused before anything
        // durable happens.
        assertTrue(ledger.spentBy("alice") > 0);
        assertFalse(runtime.admits("alice"));
        assertTrue(runtime.admits("bob"), "a budget is per tenant");

        JclawRuntime.TurnResult refused = runtime.submit("alice", new ThreadId("work"),
                ChatMessage.user("again"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.FAILED, refused.status());
        assertEquals(Optional.of(FailureKind.BUDGET_EXHAUSTED), refused.failure());
        assertTrue(runs.find(refused.run()).isEmpty(), "a refused admission leaves no run behind");

        assertThrows(JclawRuntime.TenantOverBudget.class,
                () -> runtime.enqueue("alice", new ThreadId("work"), ChatMessage.user("queued")));
    }
}
