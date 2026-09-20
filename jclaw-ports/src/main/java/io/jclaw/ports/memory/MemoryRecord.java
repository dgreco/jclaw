// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.memory;

import io.jclaw.ports.turn.Ident;
import io.jclaw.ports.turn.TurnScope;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One durable memory: a piece of text the agent or user chose to keep.
 *
 * <p>Scoped, like everything else in the harness. A memory written in one project must not surface
 * in another — retrieval is a prompt-injection surface, and cross-scope leakage would let content
 * from one workspace steer a run in a different one.
 *
 * @param tags      free-form labels for filtering; normalized to lowercase so {@code TODO} and
 *                  {@code todo} are the same tag rather than two that look identical in a listing
 * @param embedding vector for similarity ranking, when an embedding provider was configured at
 *                  write time (or {@code jclaw memory reindex} ran since); absent memories still
 *                  rank lexically and by recency
 */
public record MemoryRecord(
        MemoryId id,
        TurnScope scope,
        String text,
        List<String> tags,
        Instant createdAt,
        Optional<Embedding> embedding) {

    /** Identity of a stored memory. */
    public record MemoryId(String value) implements Ident {
        public MemoryId {
            value = Ident.validate(value, "MemoryId");
        }

        public static MemoryId fresh() {
            return new MemoryId(Ident.fresh("mem"));
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public MemoryRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(embedding, "embedding");
        tags = Objects.requireNonNull(tags, "tags").stream()
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
        if (text.isBlank()) {
            throw new IllegalArgumentException("memory text must not be blank");
        }
    }

    /** A memory with no embedding. */
    public MemoryRecord(MemoryId id, TurnScope scope, String text, List<String> tags, Instant createdAt) {
        this(id, scope, text, tags, createdAt, Optional.empty());
    }

    public MemoryRecord withEmbedding(Embedding embedding) {
        return new MemoryRecord(id, scope, text, tags, createdAt, Optional.of(embedding));
    }

    /** A short single-line form for listings. */
    public String preview(int maxChars) {
        String flattened = text.replace('\n', ' ').trim();
        return flattened.length() <= maxChars ? flattened : flattened.substring(0, maxChars) + "...";
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag.trim().toLowerCase(Locale.ROOT));
    }
}
