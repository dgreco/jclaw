// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.retrieval;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reciprocal Rank Fusion: merges several independently ranked result lists into one.
 *
 * <p>Memory retrieval runs more than one search — lexical full-text and vector similarity at
 * minimum — and their scores are not comparable. A BM25 score of 12.4 and a cosine similarity of
 * 0.83 cannot be added, averaged, or normalized without inventing a weighting that is really a
 * guess. RRF sidesteps the problem by discarding scores entirely and using only <em>rank</em>:
 *
 * <pre>
 *   score(d) = sum over rankings r of  1 / (k + rank_r(d))
 * </pre>
 *
 * <p>A document ranked highly by several retrievers beats one ranked first by a single retriever,
 * which is exactly the behaviour hybrid search wants. The constant {@code k} damps the influence
 * of top ranks so one retriever cannot dominate; 60 is the value from the original paper and works
 * well enough that tuning it is rarely worth the effort.
 *
 * <p>Pure and generic: it knows nothing about documents, only about identity and order.
 */
public final class RrfFusion {

    /** The damping constant from Cormack et al. */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /** One fused result and the score that ordered it. */
    public record Fused<T>(T item, double score, int contributingRankings) {
        public Fused {
            Objects.requireNonNull(item, "item");
        }
    }

    /**
     * Fuses ranked lists using {@link #DEFAULT_K}.
     *
     * @param rankings each inner list ordered best-first; duplicates within one list are ignored
     *                 after their first occurrence
     */
    public static <T> List<Fused<T>> fuse(List<List<T>> rankings) {
        return fuse(rankings, DEFAULT_K);
    }

    /**
     * Fuses ranked lists.
     *
     * <p>Ties are broken deterministically by first appearance, so the same inputs always produce
     * the same order — retrieval feeding a prompt must not wobble between runs.
     *
     * @param k damping constant; larger flattens the contribution of top ranks
     */
    public static <T> List<Fused<T>> fuse(List<List<T>> rankings, int k) {
        Objects.requireNonNull(rankings, "rankings");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got " + k);
        }

        // LinkedHashMap preserves first-seen order, which is what makes ties deterministic.
        Map<T, double[]> scores = new LinkedHashMap<>();
        Map<T, Integer> firstSeen = new LinkedHashMap<>();
        int position = 0;

        for (List<T> ranking : rankings) {
            Objects.requireNonNull(ranking, "ranking");
            // A duplicate inside one ranking keeps only its best (first) rank — otherwise a
            // retriever could inflate an item simply by listing it repeatedly.
            Set<T> seenInRanking = new HashSet<>();
            int rank = 0;
            for (T item : ranking) {
                if (item == null || !seenInRanking.add(item)) {
                    continue;
                }
                rank++;
                double contribution = 1.0 / (k + rank);
                double[] acc = scores.computeIfAbsent(item, ignored -> new double[]{0.0, 0.0});
                acc[0] += contribution;
                acc[1] += 1;
                firstSeen.putIfAbsent(item, position++);
            }
        }

        return scores.entrySet().stream()
                .map(entry -> new Fused<>(entry.getKey(), entry.getValue()[0], (int) entry.getValue()[1]))
                .sorted(Comparator
                        .comparingDouble((Fused<T> f) -> f.score()).reversed()
                        .thenComparingInt(f -> firstSeen.getOrDefault(f.item(), Integer.MAX_VALUE)))
                .toList();
    }

    /**
     * Fuses and returns only the items, truncated to {@code limit}. The common case at a call
     * site that just wants "the best n".
     */
    public static <T> List<T> fuseTop(List<List<T>> rankings, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        return fuse(rankings).stream().limit(limit).map(Fused::item).toList();
    }

}
