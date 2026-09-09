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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRankingTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final TurnScope SCOPE = TurnScope.local("proj", new ThreadId("t"));

    private static MemoryRecord memory(String id, String text, int ageDays) {
        return new MemoryRecord(
                new MemoryRecord.MemoryId(id), SCOPE, text, List.of(),
                T0.minusSeconds(86400L * ageDays));
    }

    private static List<String> ids(List<MemoryRecord> records) {
        return records.stream().map(record -> record.id().value()).toList();
    }

    @Test
    @DisplayName("lexical relevance beats recency for a specific query")
    void relevanceBeatsRecency() {
        List<MemoryRecord> corpus = List.of(
                memory("old-relevant", "the postgres connection pool exhausts under load", 100),
                memory("new-irrelevant", "remember to buy milk", 0),
                memory("newer-irrelevant", "the weather is fine today", 0));

        List<MemoryRecord> ranked = MemoryRanking.rank(corpus, "postgres pool", T0, 3);

        assertEquals("old-relevant", ranked.get(0).id().value(),
                "a 100-day-old exact match should outrank fresh noise");
    }

    @Test
    @DisplayName("rare terms outweigh common ones (IDF)")
    void rareTermsWeighMore() {
        // "the project" appears everywhere; "kubernetes" appears once.
        List<MemoryRecord> corpus = List.of(
                memory("a", "the project uses kubernetes for orchestration", 1),
                memory("b", "the project has a readme", 1),
                memory("c", "the project is written in java", 1),
                memory("d", "the project has tests", 1));

        List<MemoryRecord> ranked = MemoryRanking.rankLexically(corpus, "the project kubernetes");

        assertEquals("a", ranked.get(0).id().value(),
                "the document with the rare term should win despite equal common-term overlap");
    }

    @Test
    @DisplayName("documents matching nothing are excluded from lexical results")
    void nonMatchingExcluded() {
        List<MemoryRecord> corpus = List.of(
                memory("hit", "database migration strategy", 1),
                memory("miss", "unrelated content entirely", 1));

        List<MemoryRecord> ranked = MemoryRanking.rankLexically(corpus, "migration");

        assertEquals(List.of("hit"), ids(ranked));
    }

    @Test
    @DisplayName("a blank query degrades to recency, newest first")
    void blankQueryIsRecency() {
        List<MemoryRecord> corpus = List.of(
                memory("old", "first note", 10),
                memory("new", "second note", 1),
                memory("newest", "third note", 0));

        assertEquals(List.of("newest", "new", "old"), ids(MemoryRanking.rank(corpus, "  ", T0, 10)));
    }

    @Test
    @DisplayName("fusion surfaces recent items even when they do not match the query")
    void fusionIncludesRecent() {
        List<MemoryRecord> corpus = List.of(
                memory("relevant", "kubernetes ingress configuration", 50),
                memory("recent", "completely unrelated but fresh", 0));

        List<MemoryRecord> ranked = MemoryRanking.rank(corpus, "kubernetes", T0, 5);

        assertEquals("relevant", ranked.get(0).id().value());
        assertTrue(ids(ranked).contains("recent"),
                "the recency ranking should still contribute a candidate");
    }

    @Test
    @DisplayName("results are bounded by the limit")
    void respectsLimit() {
        List<MemoryRecord> corpus = List.of(
                memory("a", "alpha term", 1),
                memory("b", "beta term", 2),
                memory("c", "gamma term", 3));

        assertEquals(2, MemoryRanking.rank(corpus, "term", T0, 2).size());
        assertTrue(MemoryRanking.rank(corpus, "term", T0, 0).isEmpty());
    }

    @Test
    @DisplayName("ranking is deterministic")
    void deterministic() {
        List<MemoryRecord> corpus = List.of(
                memory("a", "shared term here", 1),
                memory("b", "shared term there", 1),
                memory("c", "shared term everywhere", 1));

        assertEquals(
                ids(MemoryRanking.rank(corpus, "shared term", T0, 3)),
                ids(MemoryRanking.rank(corpus, "shared term", T0, 3)),
                "identical inputs must produce an identical order");
    }

    @Test
    @DisplayName("an empty corpus ranks to nothing rather than failing")
    void emptyCorpus() {
        assertTrue(MemoryRanking.rank(List.of(), "anything", T0, 5).isEmpty());
        assertFalse(MemoryRanking.rank(List.of(), "", T0, 5).iterator().hasNext());
    }

    @Test
    @DisplayName("vector similarity surfaces a paraphrase that shares no term with the query")
    void vectorRankingIsFusedIn() {
        Embedding carLike = new Embedding("m", new float[]{1f, 0f});
        Embedding unrelated = new Embedding("m", new float[]{0f, 1f});
        List<MemoryRecord> corpus = List.of(
                new MemoryRecord(new MemoryRecord.MemoryId("paraphrase"), SCOPE,
                        "the automobile needs new tyres", List.of(), T0.minusSeconds(86400L * 30),
                        Optional.of(carLike)),
                new MemoryRecord(new MemoryRecord.MemoryId("noise-1"), SCOPE,
                        "buy milk", List.of(), T0, Optional.of(unrelated)),
                new MemoryRecord(new MemoryRecord.MemoryId("noise-2"), SCOPE,
                        "call the dentist", List.of(), T0, Optional.of(unrelated)));

        List<MemoryRecord> withoutVector = MemoryRanking.rank(corpus, "car", T0, 3);
        List<MemoryRecord> withVector = MemoryRanking.rank(corpus, "car", T0, 3, Optional.of(carLike));

        assertEquals("paraphrase", withVector.get(0).id().value(),
                "the vector ranking should lift the paraphrase to the top");
        assertNotEquals("paraphrase", withoutVector.get(0).id().value(),
                "without it, no term matches and recency decides");
    }

    @Test
    @DisplayName("a query embedding in a different space than the corpus changes nothing")
    void foreignSpaceIsIgnored() {
        List<MemoryRecord> corpus = List.of(
                new MemoryRecord(new MemoryRecord.MemoryId("a"), SCOPE, "alpha", List.of(), T0,
                        Optional.of(new Embedding("m", new float[]{1f, 0f}))),
                memory("b", "beta", 1));
        Embedding foreign = new Embedding("other-model", new float[]{1f, 0f});

        assertEquals(
                ids(MemoryRanking.rank(corpus, "alpha beta", T0, 2)),
                ids(MemoryRanking.rank(corpus, "alpha beta", T0, 2, Optional.of(foreign))));
    }

    @Test
    @DisplayName("tokenization is case-insensitive and splits on punctuation")
    void tokenization() {
        assertEquals(List.of("hello", "world", "42"), MemoryRanking.tokenize("Hello, World! 42"));
        assertEquals(List.of("snake", "case", "camelcase"),
                MemoryRanking.tokenize("snake_case camelCase"));
    }
}
