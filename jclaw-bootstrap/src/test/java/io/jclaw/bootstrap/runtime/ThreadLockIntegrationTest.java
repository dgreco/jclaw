// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.loop.FailureKind;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.thread.ThreadService;
import io.jclaw.ports.turn.RunStore;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.ThreadLock;
import io.jclaw.ports.turn.TurnStatus;
import io.jclaw.adapter.out.model.mock.MockModelProvider;
import io.jclaw.adapter.out.model.mock.MockModelProvider.Script;
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
 * "One active run per canonical thread", checked at the product surface.
 *
 * <p>The lock's own semantics, including across processes, are covered in the storage module.
 * What this test pins is the runtime's ordering: a refused submission must leave the transcript
 * and the run store untouched, because it was refused <em>before</em> either was written.
 */
@SpringBootTest
class ThreadLockIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-lock-it");
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
    private ThreadLock locks;

    @Autowired
    private ThreadService threads;

    @Autowired
    private RunStore runs;

    @Autowired
    private ModelProvider provider;

    @Test
    @DisplayName("a submission on a thread another run holds is refused before any side effect")
    void refusedWhileHeld() {
        ((MockModelProvider) provider).reprogram(List.of(new Script.Text("done")));
        ThreadId thread = new ThreadId("lock-it");
        int runsBefore = runs.recent(Integer.MAX_VALUE).size();

        JclawRuntime.TurnResult refused;
        ThreadLock.Held lock = locks.tryAcquire(runtime.scopeFor(thread)).orElseThrow();
        try (lock) {
            refused = runtime.submit(thread, "second process", new AtomicBoolean(false));
        }

        assertEquals(TurnStatus.FAILED, refused.status());
        assertEquals(FailureKind.THREAD_BUSY, refused.failure().orElseThrow());
        assertTrue(refused.failureDetail().orElse("").contains("lock-it"),
                "the detail should name the busy thread");
        assertTrue(threads.history(thread, 10).isEmpty(),
                "a refused submission must not accept the inbound message");
        assertEquals(runsBefore, runs.recent(Integer.MAX_VALUE).size(),
                "a refused submission must not record a run");

        // Released: the same submission now goes through, and the lock was released after it.
        JclawRuntime.TurnResult admitted = runtime.submit(thread, "retry", new AtomicBoolean(false));
        assertEquals(TurnStatus.COMPLETED, admitted.status());
        assertEquals("done", admitted.reply().orElseThrow());
        assertTrue(locks.tryAcquire(runtime.scopeFor(thread)).isPresent(),
                "a finished run must release its thread");
    }

    @Test
    @DisplayName("other threads are unaffected by a held one")
    void otherThreadsProceed() {
        ((MockModelProvider) provider).reprogram(List.of(new Script.Text("elsewhere")));
        ThreadLock.Held lock =
                locks.tryAcquire(runtime.scopeFor(new ThreadId("lock-held"))).orElseThrow();
        try (lock) {
            JclawRuntime.TurnResult result =
                    runtime.submit(new ThreadId("lock-free"), "hello", new AtomicBoolean(false));
            assertEquals(TurnStatus.COMPLETED, result.status());
        }
    }
}
