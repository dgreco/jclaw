package io.jclaw.app.runtime;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.LoopHook;
import io.jclaw.contracts.model.ChatMessage;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hook that refuses. The prompt stage refuses the whole turn before anything is written; the
 * gate stage turns the question into a denial the model is told about.
 */
@SpringBootTest
class HookVetoIntegrationTest {

    private static Path workspace;

    /** Which stage the hook should refuse on, set per test. */
    static final AtomicReference<String> refuse = new AtomicReference<>("");

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-veto-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "interactive");
    }

    static final class RefusingHook implements LoopHook {
        @Override
        public String id() {
            return "refusing";
        }

        @Override
        public Outcome<String> beforePrompt(HookContext context, String systemPrompt) {
            return "prompt".equals(refuse.get())
                    ? Outcome.veto("this tenant is suspended")
                    : Outcome.proceed(systemPrompt);
        }

        @Override
        public Outcome<String> beforeGate(HookContext context, GateRequest gate) {
            return "gate".equals(refuse.get())
                    ? Outcome.veto("out of hours")
                    : Outcome.proceed(gate.prompt());
        }
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of(
                    new Script.ToolCall("g1", "builtin.shell", Map.of("command", "pwd")),
                    new Script.Text("the tool was refused, so here is an answer")));
        }

        @Bean
        LoopHook refusingHook() {
            return new RefusingHook();
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired EventLog events;
    @Autowired ThreadService threads;

    @Test
    @DisplayName("a prompt veto fails the turn before the message is written")
    void promptVetoRefusesTheTurn() {
        refuse.set("prompt");
        ThreadId thread = new ThreadId("veto-prompt");
        JclawRuntime.TurnResult result = runtime.submit(thread,
                ChatMessage.user("do a thing"), new AtomicBoolean(false), Optional.empty());

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(Optional.of(FailureKind.POLICY_DENIED), result.failure());
        assertTrue(result.failureDetail().orElse("").contains("this tenant is suspended"));
        assertTrue(threads.history(thread, 10).isEmpty(),
                "refusing after the inbound message is durable would leave a message with no run");
    }

    @Test
    @DisplayName("a gate veto becomes a denial the model is told about, never an approval")
    void gateVetoDenies() {
        refuse.set("gate");
        ThreadId thread = new ThreadId("veto-gate");
        JclawRuntime.TurnResult result = runtime.submit(thread,
                ChatMessage.user("run something"), new AtomicBoolean(false), Optional.empty());

        assertEquals(TurnStatus.COMPLETED, result.status(),
                () -> "the run continues with a denial, it does not park: " + result.failureDetail());

        List<JclawEvent> log = events.readRun(result.run()).stream().map(EventLog.Entry::event).toList();
        assertTrue(log.stream().anyMatch(e -> e instanceof JclawEvent.CapabilityInvoked c
                        && c.outcome().equals("denied")),
                "the call did not happen");
        assertFalse(log.stream().anyMatch(e -> e instanceof JclawEvent.GateRaised),
                "no gate was raised: a hook that refuses to ask must not leave a question open");
        assertTrue(log.stream().anyMatch(e -> e instanceof JclawEvent.HookFired h
                && h.stage().equals("before-gate") && h.action().equals("vetoed")));
    }
}
