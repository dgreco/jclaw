package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.LoopHook;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hooks narrow model requests and veto capability calls, the audit log records that they did, and
 * a hook that tries to widen a request is refused rather than obeyed.
 */
@SpringBootTest
class LoopHooksIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-hooks-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
    }

    @TestConfiguration
    static class Hooks {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of(
                    new Script.ToolCall("c1", "builtin.echo", Map.of("text", "allowed")),
                    new Script.ToolCall("c2", "builtin.echo", Map.of("text", "forbidden")),
                    new Script.Text("done")));
        }

        /** Amends the prompt, rewrites one argument, and vetoes one call. */
        @Bean
        LoopHook testHook() {
            return new LoopHook() {
                @Override
                public String id() {
                    return "test-hook";
                }

                @Override
                public Outcome<ModelRequest> beforeModel(HookContext context, ModelRequest request) {
                    return Outcome.proceed(new ModelRequest(request.model(), request.system() + "\nHOOKED",
                            request.messages(), request.tools(), request.maxTokens(), request.temperature()));
                }

                @Override
                public Outcome<CapabilityInvocation> beforeCapability(HookContext context, CapabilityInvocation invocation) {
                    String text = invocation.stringArg("text", "");
                    if (text.equals("forbidden")) {
                        return Outcome.veto("the word is not allowed");
                    }
                    return Outcome.proceed(new CapabilityInvocation(invocation.capability(), invocation.callId(),
                            Map.of("text", text + " (rewritten)"), invocation.scope(), invocation.run()));
                }
            };
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired MockModelProvider provider;
    @Autowired EventLog events;
    @Autowired ThreadService threads;

    @Test
    @DisplayName("prompt amended, argument rewritten, call vetoed, all audited")
    void hooksApply() {
        JclawRuntime.TurnResult result = runtime.submit(new ThreadId("hooks"), ChatMessage.user("go"),
                new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, result.status(), () -> String.valueOf(result.failureDetail()));

        assertTrue(provider.lastRequest().orElseThrow().system().endsWith("HOOKED"), "the system prompt was amended");
        String toolResults = provider.lastRequest().orElseThrow().messages().toString();
        assertTrue(toolResults.contains("allowed (rewritten)"), "the rewritten argument reached the tool: " + toolResults);
        assertTrue(toolResults.contains("hook_vetoed"), "the model was told about the veto: " + toolResults);

        List<JclawEvent> log = events.readRun(result.run()).stream().map(EventLog.Entry::event).toList();
        List<String> fired = log.stream().filter(JclawEvent.HookFired.class::isInstance)
                .map(JclawEvent.HookFired.class::cast)
                .map(e -> e.stage() + ":" + e.action()).toList();
        assertTrue(fired.contains("before-model:rewrote"), fired.toString());
        assertTrue(fired.contains("before-capability:rewrote"), fired.toString());
        assertTrue(fired.contains("before-capability:vetoed"), fired.toString());
        long invoked = log.stream().filter(JclawEvent.CapabilityInvoked.class::isInstance).count();
        assertEquals(1, invoked, "the vetoed call never reached the kernel");
    }

    @Test
    @DisplayName("the budget notice appears only once the threshold is crossed")
    void budgetNotice() {
        BudgetNoticeHook hook = new BudgetNoticeHook(0.8);
        ModelRequest request = new ModelRequest("m", "system", List.of(ChatMessage.user("x")), List.of(), 10, Optional.empty());
        LoopHook.HookContext calm = new LoopHook.HookContext(new TurnRunId("r"), TurnScope.local("p", new ThreadId("t")), 1, 0.5);
        LoopHook.HookContext tight = new LoopHook.HookContext(new TurnRunId("r"), TurnScope.local("p", new ThreadId("t")), 1, 0.9);

        assertEquals("system", ((LoopHook.Outcome.Proceed<ModelRequest>) hook.beforeModel(calm, request)).value().system());
        String amended = ((LoopHook.Outcome.Proceed<ModelRequest>) hook.beforeModel(tight, request)).value().system();
        assertTrue(amended.contains("90%") && amended.contains("Budget notice"), amended);
    }
}
