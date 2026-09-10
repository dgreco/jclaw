// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.retrieval;

import io.jclaw.contracts.memory.Embedding;
import io.jclaw.contracts.memory.MemoryRecord;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorRankingTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final TurnScope SCOPE = TurnScope.local("proj", new ThreadId("t"));

    private static MemoryRecord memory(String id, Embedding embedding) {
        return new MemoryRecord(new MemoryRecord.MemoryId(id), SCOPE, "text " + id, List.of(), T0,
                Optional.ofNullable(embedding));
    }

    private static Embedding vec(float... values) {
        return new Embedding("m", values);
    }

    private static List<String> ids(List<MemoryRecord> records) {
        return records.stream().map(record -> record.id().value()).toList();
    }

    @Test
    @DisplayName("closest vector ranks first")
    void closestFirst() {
        List<MemoryRecord> corpus = List.of(
                memory("far", vec(1, 3)),
                memory("near", vec(1, 0.1f)),
                memory("exact", vec(1, 0)));

        assertEquals(List.of("exact", "near", "far"), ids(VectorRanking.rank(corpus, vec(1, 0))));
    }

    @Test
    @DisplayName("memories without an embedding, or from another space, are excluded")
    void excludesIncomparable() {
        List<MemoryRecord> corpus = List.of(
                memory("none", null),
                memory("other-model", new Embedding("other", new float[]{1, 0})),
                memory("other-dims", new Embedding("m", new float[]{1, 0, 0})),
                memory("ok", vec(1, 0)));

        assertEquals(List.of("ok"), ids(VectorRanking.rank(corpus, vec(1, 0))));
    }

    @Test
    @DisplayName("non-positive similarity earns no place in the ranking")
    void dropsUnrelated() {
        List<MemoryRecord> corpus = List.of(
                memory("orthogonal", vec(0, 1)),
                memory("opposed", vec(-1, 0)),
                memory("related", vec(1, 1)));

        assertEquals(List.of("related"), ids(VectorRanking.rank(corpus, vec(1, 0))));
    }

    @Test
    @DisplayName("ties keep original order")
    void deterministicTies() {
        List<MemoryRecord> corpus = List.of(
                memory("a", vec(1, 0)),
                memory("b", vec(1, 0)),
                memory("c", vec(2, 0)));

        assertEquals(List.of("a", "b", "c"), ids(VectorRanking.rank(corpus, vec(1, 0))));
    }

    @Test
    @DisplayName("an embedding refuses to be compared across spaces")
    void cosineGuardsSpace() {
        assertThrows(IllegalArgumentException.class,
                () -> vec(1, 0).cosine(new Embedding("other", new float[]{1, 0})));
        assertEquals(1.0, vec(3, 4).cosine(vec(3, 4)), 1e-9);
        assertTrue(new Embedding("m", new float[]{1}).equals(new Embedding("m", new float[]{1})));
    }
}
