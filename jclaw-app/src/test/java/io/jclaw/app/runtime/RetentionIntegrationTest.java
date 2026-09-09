package io.jclaw.app.runtime;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ModelProvider;
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
 * Retention drops old rows of finished runs and nothing else: a parked run keeps its
 * checkpoint and events however old they are.
 */
@SpringBootTest
class RetentionIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-retain-it");
        Files.writeString(workspace.resolve("notes.md"), "kept for a while");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "interactive");
        registry.add("jclaw.retention-results", () -> "1s");
        registry.add("jclaw.retention-events", () -> "1s");
        registry.add("jclaw.retention-checkpoints", () -> "1s");
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
    private RetentionService retention;

    @Autowired
    private EventLog events;

    @Autowired
    private ModelProvider provider;

    @Test
    @DisplayName("old rows of a finished run go; a parked run keeps everything")
    void sweepsFinishedOnly() throws InterruptedException {
        MockModelProvider mock = (MockModelProvider) provider;

        // A finished run with a result, events, and checkpoints.
        mock.reprogram(List.of(
                new Script.ToolCall("r1", "builtin.read_file", Map.of("path", "notes.md")),
                new Script.Text("read it")));
        JclawRuntime.TurnResult done = runtime.submit(new ThreadId("retain-done"), "read", new AtomicBoolean(false));
        assertEquals(TurnStatus.COMPLETED, done.status());

        // A parked run: its rows must survive any sweep.
        mock.reprogram(List.of(
                new Script.ToolCall("w1", "builtin.write_file", Map.of("path", "x.txt", "content", "x")),
                new Script.Text("never reached")));
        JclawRuntime.TurnResult parked = runtime.submit(new ThreadId("retain-parked"), "write", new AtomicBoolean(false));
        assertEquals(TurnStatus.BLOCKED_APPROVAL, parked.status());

        Thread.sleep(1_200);

        List<RetentionService.Swept> dry = retention.sweep(true);
        assertTrue(dry.stream().allMatch(s -> !s.applied()), "a dry run rewrites nothing");
        assertTrue(dry.stream().anyMatch(s -> s.dropped() > 0), "there is something to drop");

        List<RetentionService.Swept> real = retention.sweep(false);
        assertTrue(real.stream().anyMatch(s -> s.store().equals("events") && s.applied()));

        assertTrue(events.readRun(done.run()).isEmpty(), "the finished run's events are gone");
        assertFalse(events.readRun(parked.run()).isEmpty(), "the parked run's events remain");

        // The parked run still resumes: its checkpoint survived.
        JclawRuntime.TurnResult again = runtime.resume(parked.run(), new AtomicBoolean(false));
        assertEquals(TurnStatus.BLOCKED_APPROVAL, again.status());
    }
}
