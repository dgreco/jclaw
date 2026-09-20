// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.lock;

import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.ThreadLock;
import io.jclaw.ports.turn.TurnScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileThreadLockTest {

    private static final TurnScope THREAD_A = TurnScope.local("proj", new ThreadId("a"));
    private static final TurnScope THREAD_B = TurnScope.local("proj", new ThreadId("b"));

    @TempDir
    Path dir;

    @Test
    @DisplayName("a held lock refuses a second acquirer until it is released")
    void exclusiveWithinProcess() {
        FileThreadLock locks = new FileThreadLock(dir);

        Optional<ThreadLock.Held> first = locks.tryAcquire(THREAD_A);
        assertTrue(first.isPresent(), "an uncontended lock should be acquired");
        assertTrue(locks.tryAcquire(THREAD_A).isEmpty(), "the same thread must be refused while held");

        first.get().close();
        assertTrue(locks.tryAcquire(THREAD_A).isPresent(), "release must make it acquirable again");
    }

    @Test
    @DisplayName("different threads do not contend")
    void independentThreads() {
        FileThreadLock locks = new FileThreadLock(dir);
        try (ThreadLock.Held a = locks.tryAcquire(THREAD_A).orElseThrow()) {
            assertTrue(locks.tryAcquire(THREAD_B).isPresent(), "another thread is unaffected");
            assertEquals(THREAD_A, a.scope());
        }
    }

    @Test
    @DisplayName("the same thread in a different project is a different lock")
    void projectIsPartOfTheKey() {
        FileThreadLock locks = new FileThreadLock(dir);
        TurnScope otherProject = TurnScope.local("other", new ThreadId("a"));
        assertNotEquals(locks.lockFileFor(THREAD_A), locks.lockFileFor(otherProject));
        ThreadLock.Held held = locks.tryAcquire(THREAD_A).orElseThrow();
        try (held) {
            assertTrue(locks.tryAcquire(otherProject).isPresent());
        }
    }

    @Test
    @DisplayName("closing twice is harmless")
    void idempotentRelease() {
        FileThreadLock locks = new FileThreadLock(dir);
        ThreadLock.Held held = locks.tryAcquire(THREAD_A).orElseThrow();
        held.close();
        held.close();
        assertTrue(locks.tryAcquire(THREAD_A).isPresent());
    }

    /**
     * The property the lock exists for: a <em>separate process</em> holding the thread is refused
     * here, and its death releases the thread with no TTL to wait out.
     */
    @Test
    @DisplayName("a lock held by another process is refused, and its exit releases it")
    void exclusiveAcrossProcesses() throws Exception {
        FileThreadLock locks = new FileThreadLock(dir);
        Path file = locks.lockFileFor(THREAD_A);

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process holder = new ProcessBuilder(List.of(
                java, "-cp", System.getProperty("java.class.path"),
                Holder.class.getName(), file.toString()))
                .redirectErrorStream(true)
                .start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream()));
            assertEquals("held", out.readLine(), "the child should report it holds the lock");

            assertTrue(locks.tryAcquire(THREAD_A).isEmpty(),
                    "a lock held by another process must be refused");
            assertTrue(locks.tryAcquire(THREAD_B).isPresent(), "other threads stay free");
        } finally {
            holder.destroyForcibly();
            assertTrue(holder.waitFor(10, TimeUnit.SECONDS), "the child should die on demand");
        }

        // No grace period, no reconciliation: the OS dropped the lock with the process.
        assertTrue(locks.tryAcquire(THREAD_A).isPresent(),
                "the thread must be acquirable as soon as the holder is gone");
    }

    /** Child-process entry point: locks the given file, says so, and holds it until killed. */
    public static final class Holder {
        public static void main(String[] args) throws IOException, InterruptedException {
            Path file = Path.of(args[0]);
            try (FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = channel.lock();
                try (lock) {
                    System.out.println("held");
                    System.out.flush();
                    Thread.sleep(60_000);
                }
            }
        }
    }
}
