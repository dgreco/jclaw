package io.jclaw.app.runtime;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Subagents run as ordinary child turns on the same machinery.
 *
 * <p>The assertions that matter are not "it returned a string" but that the child produced a
 * <em>separate run record</em> and a <em>separate thread</em>. That is what proves it went through
 * the real turn path rather than a shortcut, which is the whole architectural claim.
 */
@SpringBootTest
class SubagentIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-subagent-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        // spawn_subagent is PROCESS-class, so it gates under the default policy. Trusted here so
        // the delegation path itself is what is under test, not the approval path.
        registry.add("jclaw.approval-mode", () -> "trusted");
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
    private RunStore runs;

    @Autowired
    private ThreadService threads;

    @Autowired
    private EventLog events;

    private void script(Script... turns) {
        ((MockModelProvider) provider).reprogram(List.of(turns));
    }

    @Test
    @DisplayName("a delegated task runs as a real child turn and returns only its conclusion")
    void delegatesToChildRun() {
        script(
                // Parent delegates.
                new Script.ToolCall("s1", "builtin.spawn_subagent", Map.of(
                        "description", "count",
                        "prompt", "How many files are in the workspace?")),
                // The child's own turn consumes the next script entry.
                new Script.Text("There are 3 files."),
                // Parent continues with the child's conclusion in hand.
                new Script.Text("The subagent reports 3 files."));

        JclawRuntime.TurnResult parent =
                runtime.submit(new ThreadId("parent"), "delegate this", new AtomicBoolean(false));

        assertEquals(TurnStatus.COMPLETED, parent.status());
        assertEquals("The subagent reports 3 files.", parent.reply().orElseThrow());

        // The child was a genuine run, not an inline call: it has its own record.
        List<RunStore.RunRecord> allRuns = runs.recent(20);
        assertTrue(allRuns.size() >= 2, "parent and child should each have a run record");

        // And its own thread, so the parent's transcript stays free of the child's detail.
        boolean childThreadExists = threads.listThreads(20).stream()
                .anyMatch(thread -> thread.value().startsWith("parent~sub"));
        assertTrue(childThreadExists, "the child should run in its own derived thread");
    }

    @Test
    @DisplayName("the child runs in a different scope thread than its parent")
    void childIsIsolatedFromParentTranscript() {
        script(
                new Script.ToolCall("s2", "builtin.spawn_subagent",
                        Map.of("prompt", "summarize something")),
                new Script.Text("Summary produced."),
                new Script.Text("Done."));

        runtime.submit(new ThreadId("isolated"), "go", new AtomicBoolean(false));

        List<ThreadService.ThreadMessage> parentHistory =
                threads.history(new ThreadId("isolated"), 20);

        // The child's prompt must not appear in the parent's transcript — keeping detail out of
        // the parent's context is the entire reason to delegate.
        boolean leaked = parentHistory.stream()
                .anyMatch(message -> message.message().displayText().contains("summarize something"));
        assertTrue(!leaked, "the child's prompt must not land in the parent's transcript");
    }

    @Test
    @DisplayName("nesting is bounded so an agent cannot recurse without limit")
    void depthIsBounded() {
        // Thread ids encode depth; at MAX_DEPTH the host refuses rather than spawning.
        ThreadId deep = new ThreadId("root~sub1-a~sub2-b~sub3-c");
        assertEquals(3, RuntimeSubagentHost.depthOf(deep));
        assertEquals(0, RuntimeSubagentHost.depthOf(new ThreadId("root")));

        script(
                new Script.ToolCall("s3", "builtin.spawn_subagent", Map.of("prompt", "go deeper")),
                new Script.Text("I could not delegate further."));

        JclawRuntime.TurnResult result =
                runtime.submit(deep, "try to nest", new AtomicBoolean(false));

        // The turn still completes: refusal is information the model handles, not a crash.
        assertEquals(TurnStatus.COMPLETED, result.status());

        String capabilityEvents = events.readRun(result.run()).stream()
                .map(EventLog.Entry::event)
                .filter(event -> event.type().equals("capability.invoked"))
                .map(Object::toString)
                .reduce("", String::concat);
        assertNotEquals("", capabilityEvents, "the refused spawn should still be audited");
    }
}
