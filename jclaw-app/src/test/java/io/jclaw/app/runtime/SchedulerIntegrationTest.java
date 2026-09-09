package io.jclaw.app.runtime;

import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnStatus;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queued work is executed by the scheduler under the caps: bounded concurrency, one run per thread
 * at a time, oldest first.
 */
@SpringBootTest
class SchedulerIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-sched-it");
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
            // Every call gets the same reply, so concurrent runs do not compete for a script.
            return MockModelProvider.alwaysReplying("scheduled reply");
        }
    }

    @Autowired
    private JclawRuntime runtime;

    @Autowired
    private TurnRunScheduler scheduler;

    @Autowired
    private RunStore runs;

    @Autowired
    private ThreadService threads;

    @Autowired
    private ModelProvider provider;

    private TurnStatus status(TurnRunId run) {
        return runs.find(run).orElseThrow().status();
    }

    @Test
    @DisplayName("submit enqueues durably; the scheduler executes one run per thread per pass")
    void queuedRunsExecuteUnderCaps() {
        ThreadId a = new ThreadId("sched-a");
        ThreadId b = new ThreadId("sched-b");
        TurnRunId a1 = runtime.enqueue(a, "first on a");
        TurnRunId a2 = runtime.enqueue(a, "second on a");
        TurnRunId b1 = runtime.enqueue(b, "only on b");

        assertEquals(TurnStatus.QUEUED, status(a1));
        assertEquals(2, threads.history(a, 10).size(), "both inbound messages are durable before any run");

        List<TurnRunScheduler.Outcome> first = scheduler.runOnce(4, new AtomicBoolean(false));

        assertEquals(2, first.size(), "a1 and b1 start; a2 waits for a1's thread");
        assertEquals(TurnStatus.COMPLETED, status(a1));
        assertEquals(TurnStatus.COMPLETED, status(b1));
        assertEquals(TurnStatus.QUEUED, status(a2));
        assertTrue(first.stream().allMatch(outcome -> outcome.result().isSuccess()));

        List<TurnRunScheduler.Outcome> second = scheduler.runOnce(4, new AtomicBoolean(false));
        assertEquals(1, second.size());
        assertEquals(a2, second.get(0).run());
        assertEquals(TurnStatus.COMPLETED, status(a2));
        assertEquals(0, scheduler.inFlight());

        // The transcript is the truthful log: both inbound messages were accepted before either
        // ran. The second run nevertheless saw the conversation as of its submission, with the
        // first reply in place and its own message as the current turn.
        List<String> history = threads.history(a, 10).stream()
                .map(message -> message.message().displayText()).toList();
        assertEquals(List.of("first on a", "second on a", "scheduled reply", "scheduled reply"), history);
        List<String> seenByA2 = ((MockModelProvider) provider).lastRequest().orElseThrow().messages().stream()
                .map(io.jclaw.contracts.model.ChatMessage::displayText).toList();
        assertEquals(List.of("first on a", "scheduled reply", "second on a"), seenByA2);
    }

    @Test
    @DisplayName("the concurrency cap bounds a pass")
    void capBoundsPass() {
        TurnRunId x = runtime.enqueue(new ThreadId("cap-x"), "x");
        TurnRunId y = runtime.enqueue(new ThreadId("cap-y"), "y");

        List<TurnRunScheduler.Outcome> pass = scheduler.runOnce(1, new AtomicBoolean(false));

        assertEquals(1, pass.size());
        assertEquals(x, pass.get(0).run(), "oldest first");
        assertEquals(TurnStatus.QUEUED, status(y));
        scheduler.runOnce(1, new AtomicBoolean(false));
        assertEquals(TurnStatus.COMPLETED, status(y));
    }
}
