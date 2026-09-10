// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.retrieval;

import io.jclaw.contracts.memory.Embedding;
import io.jclaw.contracts.memory.MemoryRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure vector ranking of memories by cosine similarity to a query embedding.
 *
 * <p>The third list into {@link RrfFusion}, next to lexical and recency. It sees what the other
 * two cannot: a memory about "the automobile" for a query about "the car", where BM25 finds no
 * shared term and recency is indifferent.
 *
 * <p>Only memories embedded in the <em>same space</em> as the query take part. A memory written
 * before embeddings were configured, or under a different embedding model, simply does not appear
 * in this ranking; it still competes through the other two. {@code jclaw memory reindex} embeds
 * such memories retroactively.
 *
 * <p>Pure: no model call happens here. The query embedding arrives as a parameter, computed by the
 * caller through the {@code EmbeddingProvider} port, so ranking stays testable with plain values.
 */
public final class VectorRanking {

    private VectorRanking() {
    }

    /**
     * Ranks by cosine similarity, best first. Memories with no comparable embedding are excluded,
     * as are non-positive similarities: an orthogonal or opposed vector is not evidence of
     * relevance and must not earn a fusion contribution just by existing.
     */
    public static List<MemoryRecord> rank(List<MemoryRecord> candidates, Embedding query) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(query, "query");

        record Scored(MemoryRecord record, double score, int index) {
        }

        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            MemoryRecord candidate = candidates.get(i);
            if (candidate.embedding().isEmpty()) {
                continue;
            }
            Embedding embedding = candidate.embedding().get();
            if (!embedding.sameSpaceAs(query)) {
                continue;
            }
            double similarity = query.cosine(embedding);
            if (similarity > 0.0) {
                scored.add(new Scored(candidate, similarity, i));
            }
        }

        // Ties break by original position so ordering is deterministic across runs.
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparingInt(Scored::index))
                .map(Scored::record)
                .toList();
    }
}
