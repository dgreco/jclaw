package io.jclaw.contracts.memory;

import io.jclaw.contracts.turn.Ident;
import io.jclaw.contracts.turn.TurnScope;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One durable memory: a piece of text the agent or user chose to keep.
 *
 * <p>Scoped, like everything else in the harness. A memory written in one project must not surface
 * in another — retrieval is a prompt-injection surface, and cross-scope leakage would let content
 * from one workspace steer a run in a different one.
 *
 * @param tags free-form labels for filtering; normalized to lowercase so {@code TODO} and
 *             {@code todo} are the same tag rather than two that look identical in a listing
 */
public record MemoryRecord(
        MemoryId id,
        TurnScope scope,
        String text,
        List<String> tags,
        Instant createdAt) {

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
        tags = Objects.requireNonNull(tags, "tags").stream()
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
        if (text.isBlank()) {
            throw new IllegalArgumentException("memory text must not be blank");
        }
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
