package io.jclaw.app.runtime;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.LoopHook;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two stages hooks did not reach: the prompt as it is assembled, and a gate as it is raised.
 */
@SpringBootTest
class HookStagesIntegrationTest {

    private static Path workspace;

    /** What the prompt hook saw, so the test can prove it ran at admission and only once. */
    static final List<String> promptsSeen = new CopyOnWriteArrayList<>();
    static final List<String> gatesSeen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-hooks-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        // interactive so a PROCESS-class capability raises a gate rather than being denied.
        registry.add("jclaw.approval-mode", () -> "interactive");
    }

    /** Amends the prompt and the gate prompt, and refuses nothing — refusal has its own test. */
    static final class AmendingHook implements LoopHook {
        @Override
        public String id() {
            return "amending";
        }

        @Override
        public Outcome<String> beforePrompt(HookContext context, String systemPrompt) {
            promptsSeen.add(systemPrompt);
            return Outcome.proceed(systemPrompt + "\n\n## House rule\nAlways cite a file.");
        }

        @Override
        public Outcome<String> beforeGate(HookContext context, GateRequest gate) {
            gatesSeen.add(gate.kind().name() + " " + gate.capability().orElse("-"));
            return Outcome.proceed("[ticket OPS-1] " + gate.prompt());
        }
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of(
                    new Script.ToolCall("g1", "builtin.shell", Map.of("command", "pwd")),
                    new Script.Text("unreachable: the run parks on the gate")));
        }

        @Bean
        LoopHook amendingHook() {
            return new AmendingHook();
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired EventLog events;
    @Autowired RunStore runs;
    @Autowired io.jclaw.storage.approval.JsonlApprovalStore approvals;

    @Test
    @DisplayName("the prompt hook runs once at admission, and what it returns is stored on the run")
    void promptAmendedAndStored() {
        promptsSeen.clear();
        gatesSeen.clear();

        JclawRuntime.TurnResult result = runtime.submit(new ThreadId("hooks"),
                ChatMessage.user("run something"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.BLOCKED_APPROVAL, result.status(),
                () -> "expected a gate, got " + result.status() + " " + result.failureDetail());

        assertEquals(1, promptsSeen.size(), "assembled once per turn, not once per model call");
        assertFalse(promptsSeen.get(0).contains("House rule"), "the hook saw the prompt before its own edit");

        TurnRunId run = result.run();
        String stored = runs.find(run).orElseThrow().systemPrompt();
        assertTrue(stored.contains("House rule"),
                "what the hook returned is what a resume will replay");

        List<JclawEvent> log = events.readRun(run).stream().map(EventLog.Entry::event).toList();
        assertTrue(log.stream().anyMatch(e -> e instanceof JclawEvent.HookFired h
                        && h.stage().equals("before-prompt") && h.action().equals("amended")),
                "the audit log records that a hook changed the prompt");

        // And the gate hook ran on the way to parking.
        assertEquals(List.of("APPROVAL builtin.shell"), gatesSeen);
        assertTrue(log.stream().anyMatch(e -> e instanceof JclawEvent.HookFired h
                && h.stage().equals("before-gate") && h.action().equals("amended")));
        var scope = runs.find(run).orElseThrow().scope();
        assertTrue(approvals.pending(scope).stream()
                        .anyMatch(gate -> gate.prompt().startsWith("[ticket OPS-1] ")),
                "the human is asked the question the hook worded: "
                        + approvals.pending(scope).stream().map(g -> g.prompt()).toList());
    }
}
