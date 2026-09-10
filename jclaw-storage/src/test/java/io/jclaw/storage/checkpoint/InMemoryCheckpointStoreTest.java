// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.checkpoint;

import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.CheckpointStore;
import io.jclaw.contracts.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.contracts.turn.TurnRunId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory checkpoint store, which is what a single-process CLI resumes from.
 *
 * <p>What is worth pinning is the ref contract rather than the map: every write mints a fresh ref
 * that resolves to exactly that checkpoint, and {@code latestFor} answers per run. A resume reads
 * a checkpoint by ref, so a ref that resolved to the wrong one — or to a later run's — would
 * replay the wrong state rather than fail.
 */
class InMemoryCheckpointStoreTest {

    private static final TurnRunId RUN = new TurnRunId("run_aaaa");
    private static final TurnRunId OTHER_RUN = new TurnRunId("run_bbbb");

    /** Advances only when the test says so, so "latest" is decided by order and not by speed. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T12:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private static byte[] payload(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a written checkpoint resolves by its ref, with everything it was given")
    void writeThenResolve() {
        var store = new InMemoryCheckpointStore(new TestClock());

        LoopCheckpointStateRef ref = store.write(RUN, CheckpointKind.BEFORE_MODEL, 3, 7, payload("state"));
        CheckpointStore.Checkpoint found = store.resolve(ref).orElseThrow();

        assertEquals(RUN, found.run());
        assertEquals(CheckpointKind.BEFORE_MODEL, found.kind());
        assertEquals(3, found.iteration());
        assertEquals(7, found.schemaVersion());
        assertArrayEquals(payload("state"), found.payload());
        assertEquals(ref, found.ref());
    }

    @Test
    @DisplayName("every write mints its own ref: two identical writes are two checkpoints")
    void refsAreDistinct() {
        var store = new InMemoryCheckpointStore(new TestClock());

        LoopCheckpointStateRef first = store.write(RUN, CheckpointKind.BEFORE_MODEL, 1, 1, payload("a"));
        LoopCheckpointStateRef second = store.write(RUN, CheckpointKind.BEFORE_MODEL, 1, 1, payload("a"));

        assertNotEquals(first, second, "a ref identifies one write, not one shape of write");
        assertEquals(2, store.size());
        assertArrayEquals(payload("a"), store.resolve(first).orElseThrow().payload());
    }

    @Test
    @DisplayName("a ref nothing minted resolves to empty rather than to something plausible")
    void unknownRefResolvesEmpty() {
        var store = new InMemoryCheckpointStore(new TestClock());
        store.write(RUN, CheckpointKind.BEFORE_MODEL, 1, 1, payload("real"));

        assertEquals(Optional.empty(), store.resolve(new LoopCheckpointStateRef("ckpt_invented")));
    }

    @Test
    @DisplayName("latestFor answers per run, and by when it was written")
    void latestIsPerRunAndByTime() {
        TestClock clock = new TestClock();
        var store = new InMemoryCheckpointStore(clock);

        store.write(RUN, CheckpointKind.BEFORE_MODEL, 1, 1, payload("first"));
        clock.advance(Duration.ofSeconds(1));
        store.write(OTHER_RUN, CheckpointKind.BEFORE_MODEL, 1, 1, payload("another run"));
        clock.advance(Duration.ofSeconds(1));
        store.write(RUN, CheckpointKind.BEFORE_CAPABILITY, 2, 1, payload("second"));

        CheckpointStore.Checkpoint latest = store.latestFor(RUN).orElseThrow();
        assertArrayEquals(payload("second"), latest.payload());
        assertEquals(CheckpointKind.BEFORE_CAPABILITY, latest.kind());

        assertArrayEquals(payload("another run"), store.latestFor(OTHER_RUN).orElseThrow().payload(),
                "one run's checkpoints must never answer for another's");
        assertTrue(store.latestFor(new TurnRunId("run_never")).isEmpty());
    }

    @Test
    @DisplayName("nulls are refused where they would otherwise be stored and resolved later")
    void nullsRefused() {
        var store = new InMemoryCheckpointStore(new TestClock());

        assertThrows(NullPointerException.class,
                () -> store.write(null, CheckpointKind.BEFORE_MODEL, 1, 1, payload("x")));
        assertThrows(NullPointerException.class,
                () -> store.write(RUN, null, 1, 1, payload("x")));
        assertThrows(NullPointerException.class,
                () -> store.write(RUN, CheckpointKind.BEFORE_MODEL, 1, 1, null));
        assertThrows(NullPointerException.class, () -> store.resolve(null));
        assertThrows(NullPointerException.class, () -> store.latestFor(null));
        assertThrows(NullPointerException.class, () -> new InMemoryCheckpointStore(null));
    }
}
