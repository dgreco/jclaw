// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.retrieval;

import io.jclaw.contracts.memory.Embedding;
import io.jclaw.contracts.memory.MemoryRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure hybrid ranking of memories: lexical relevance, recency, and vector similarity, fused.
 *
 * <p>Up to three independent rankings, combined by {@link RrfFusion}:
 *
 * <ul>
 *   <li><b>Lexical</b> — Okapi BM25 over the memory text. Rewards rare query terms and dampens the
 *       advantage of long documents that merely contain more words.</li>
 *   <li><b>Recency</b> — newest first, ignoring the query entirely.</li>
 *   <li><b>Vector</b> — cosine similarity to the query's embedding, when the caller supplies one
 *       ({@link VectorRanking}). Catches paraphrase and synonymy that BM25 cannot.</li>
 * </ul>
 *
 * <p>Fusing them rather than blending scores is the whole reason RRF is here: a BM25 score and an
 * age in seconds have no common unit, and any weighted sum of them is a guess dressed as a formula.
 * RRF discards magnitudes and uses only rank, so "recent" and "relevant" can disagree without one
 * silently dominating.
 *
 * <p>The vector ranking is additive by construction: it is one more list handed to {@code fuse},
 * present when an embedding provider is configured and the query could be embedded, absent
 * otherwise. Retrieval never depends on it. The query embedding is a parameter because computing
 * it is an effect; this class stays pure.
 *
 * <p>Pure: {@code now} is a parameter, so the same corpus and query always produce the same order.
 */
public final class MemoryRanking {

    /** BM25 term-frequency saturation. 1.2 is the conventional default. */
    private static final double K1 = 1.2;

    /** BM25 length normalization. 0.75 is the conventional default. */
    private static final double B = 0.75;

    private static final Pattern TOKEN = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

    private MemoryRanking() {
    }

    /**
     * Ranks {@code candidates} against {@code query}.
     *
     * <p>An empty or blank query degrades to pure recency, which is the sensible reading of
     * "show me my memories" rather than an error.
     *
     * @param now used only to order by recency; supplied so the function stays pure
     */
    public static List<MemoryRecord> rank(
            List<MemoryRecord> candidates, String query, Instant now, int limit) {
        return rank(candidates, query, now, limit, Optional.empty());
    }

    /**
     * Ranks {@code candidates} against {@code query}, with vector similarity as a third signal
     * when {@code queryEmbedding} is present.
     */
    public static List<MemoryRecord> rank(
            List<MemoryRecord> candidates, String query, Instant now, int limit,
            Optional<Embedding> queryEmbedding) {

        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(queryEmbedding, "queryEmbedding");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<MemoryRecord> byRecency = candidates.stream()
                .sorted(Comparator.comparing(MemoryRecord::createdAt).reversed())
                .toList();

        if (query.isBlank()) {
            return byRecency.stream().limit(limit).toList();
        }

        List<List<MemoryRecord>> rankings = new ArrayList<>();
        rankings.add(rankLexically(candidates, query));
        rankings.add(byRecency);
        queryEmbedding.ifPresent(embedding -> {
            List<MemoryRecord> byVector = VectorRanking.rank(candidates, embedding);
            if (!byVector.isEmpty()) {
                rankings.add(byVector);
            }
        });
        return RrfFusion.fuseTop(rankings, limit);
    }

    /** BM25 ranking, best first. Memories matching no query term are dropped entirely. */
    public static List<MemoryRecord> rankLexically(List<MemoryRecord> candidates, String query) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(query, "query");

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty() || candidates.isEmpty()) {
            return List.of();
        }

        List<List<String>> documents = candidates.stream().map(m -> tokenize(m.text())).toList();
        double averageLength = documents.stream().mapToInt(List::size).average().orElse(1.0);
        Map<String, Integer> documentFrequency = documentFrequencies(documents);
        int corpusSize = documents.size();

        record Scored(MemoryRecord record, double score, int index) {
        }

        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            double score = bm25(queryTerms, documents.get(i), documentFrequency, corpusSize, averageLength);
            if (score > 0) {
                scored.add(new Scored(candidates.get(i), score, i));
            }
        }

        // Ties break by original position so ordering is deterministic across runs.
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparingInt(Scored::index))
                .map(Scored::record)
                .toList();
    }

    private static double bm25(
            List<String> queryTerms,
            List<String> document,
            Map<String, Integer> documentFrequency,
            int corpusSize,
            double averageLength) {

        Map<String, Integer> termFrequency = new HashMap<>();
        for (String term : document) {
            termFrequency.merge(term, 1, Integer::sum);
        }

        double score = 0.0;
        for (String term : queryTerms) {
            int frequency = termFrequency.getOrDefault(term, 0);
            if (frequency == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            // Probabilistic IDF, floored at zero: a term in every document carries no signal and
            // must not contribute negatively.
            double idf = Math.max(0.0, Math.log(1.0 + (corpusSize - df + 0.5) / (df + 0.5)));
            double normalization = K1 * (1 - B + B * (document.size() / Math.max(1.0, averageLength)));
            score += idf * (frequency * (K1 + 1)) / (frequency + normalization);
        }
        return score;
    }

    private static Map<String, Integer> documentFrequencies(List<List<String>> documents) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (List<String> document : documents) {
            document.stream().distinct().forEach(term -> frequencies.merge(term, 1, Integer::sum));
        }
        return frequencies;
    }

    /** Lowercase alphanumeric tokens. Deliberately simple — no stemming, no stopword list. */
    static List<String> tokenize(String text) {
        return TOKEN.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isEmpty())
                .toList();
    }
}
