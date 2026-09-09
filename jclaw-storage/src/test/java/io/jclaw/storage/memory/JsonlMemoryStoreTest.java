package io.jclaw.storage.memory;

import io.jclaw.contracts.memory.Embedding;
import io.jclaw.contracts.memory.MemoryRecord;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.storage.jsonl.JsonlFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlMemoryStoreTest {

    private static final TurnScope SCOPE = TurnScope.local("proj", new ThreadId("t"));
    private static final Embedding VECTOR = new Embedding("nomic-embed-text", new float[]{0.25f, -1f, 3f});

    @TempDir
    Path dir;

    private JsonlMemoryStore store() {
        return new JsonlMemoryStore(new JsonlFile(dir.resolve("memory.jsonl")),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("an embedding written with a memory survives a replay")
    void embeddingRoundTrips() {
        JsonlMemoryStore store = store();
        MemoryRecord.MemoryId id = store.write(SCOPE, "the car is red", List.of("fact"), Optional.of(VECTOR));

        MemoryRecord read = store().find(id).orElseThrow();
        assertEquals(Optional.of(VECTOR), read.embedding(), "model and values must round-trip exactly");
        assertEquals("the car is red", read.text());
    }

    @Test
    @DisplayName("a memory written without an embedding can be embedded later")
    void attachEmbedding() {
        JsonlMemoryStore store = store();
        MemoryRecord.MemoryId id = store.write(SCOPE, "unembedded", List.of());
        assertTrue(store.find(id).orElseThrow().embedding().isEmpty());

        assertTrue(store.attachEmbedding(id, VECTOR));
        assertEquals(Optional.of(VECTOR), store().find(id).orElseThrow().embedding());
    }

    @Test
    @DisplayName("attaching to an unknown or deleted memory is refused")
    void attachToMissing() {
        JsonlMemoryStore store = store();
        assertFalse(store.attachEmbedding(new MemoryRecord.MemoryId("mem-nope"), VECTOR));

        MemoryRecord.MemoryId id = store.write(SCOPE, "gone soon", List.of());
        store.delete(id);
        assertFalse(store.attachEmbedding(id, VECTOR));
        assertTrue(store.find(id).isEmpty(), "a tombstone wins over a later embedding row");
    }

    @Test
    @DisplayName("scope isolation still holds with embeddings present")
    void scopeIsolation() {
        JsonlMemoryStore store = store();
        store.write(SCOPE, "mine", List.of(), Optional.of(VECTOR));
        store.write(TurnScope.local("other", new ThreadId("t")), "theirs", List.of(), Optional.of(VECTOR));

        assertEquals(1, store.count(SCOPE));
        assertEquals("mine", store.all(SCOPE).get(0).text());
    }
}
