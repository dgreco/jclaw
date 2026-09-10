// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * A run another host is executing: RUNNING in the shared store, leased to a worker that is
     * not this process.
     *
     * <p>This is what a second host looks like from here, and it is the only honest way to write
     * it in one JVM. Two schedulers over one {@link JclawRuntime} would share its worker id, so
     * {@code claim} would renew rather than contend and neither would ever refuse the other —
     * which is the mistake the first draft of this test made.
     */
    private TurnRunId runningOnAnotherHost(String thread) {
        TurnRunId run = runtime.enqueue(new ThreadId(thread), "work for the other host");
        assertTrue(runs.claim(run, "another-host", Instant.now().plus(Duration.ofMinutes(2))),
                "the other host takes the lease first");
        runs.updateStatus(run, TurnStatus.RUNNING);
        return run;
    }

    /** Finishes a fabricated run, so it stops occupying a slot for every later test. */
    private void finish(TurnRunId run) {
        if (status(run) == TurnStatus.QUEUED) {
            runs.updateStatus(run, TurnStatus.RUNNING);
        }
        runs.updateStatus(run, TurnStatus.COMPLETED);
    }

    /** Whether a tick started this test's own run. The store is shared, so nothing else is asserted. */
    private boolean ticksInclude(int cap, TurnRunId mine) {
        return scheduler.tick(cap, new AtomicBoolean(false)).contains(mine);
    }

    @Test
    @DisplayName("the cap counts what the deployment is running, not what this process is")
    void capIsSharedAcrossHosts() {
        TurnRunId elsewhere = runningOnAnotherHost("shared-cap-elsewhere");
        TurnRunId mine = runtime.enqueue(new ThreadId("shared-cap-mine"), "work for me");

        assertFalse(ticksInclude(1, mine),
                "one run is already executing across the deployment, so a cap of 1 is spent");
        assertEquals(TurnStatus.QUEUED, status(mine));

        finish(elsewhere);
        assertTrue(ticksInclude(4, mine), "the slot freed and this host took the queued run");
        scheduler.drain();
    }

    @Test
    @DisplayName("a thread another host is running is not even attempted")
    void anotherHostsThreadIsBusy() {
        TurnRunId elsewhere = runningOnAnotherHost("contended-thread");
        TurnRunId queued = runtime.enqueue(new ThreadId("contended-thread"), "second turn");

        assertFalse(ticksInclude(8, queued),
                "the thread lock would refuse it anyway; not picking it is the cheaper answer");
        assertEquals(TurnStatus.QUEUED, status(queued));

        finish(elsewhere);
        assertTrue(ticksInclude(8, queued));
        scheduler.drain();
    }

    @Test
    @DisplayName("a queued run another host claimed first is skipped, not started twice")
    void aRunClaimedElsewhereIsSkipped() {
        TurnRunId contended = runtime.enqueue(new ThreadId("claim-race"), "who gets this");
        // The other host wins the lease between this host selecting the run and starting it.
        assertTrue(runs.claim(contended, "another-host", Instant.now().plus(Duration.ofMinutes(2))));

        assertFalse(ticksInclude(8, contended),
                "claiming at selection is what makes the store arbitrate the race");
        assertEquals(TurnStatus.QUEUED, status(contended));

        finish(contended);
        scheduler.drain();
    }
}
