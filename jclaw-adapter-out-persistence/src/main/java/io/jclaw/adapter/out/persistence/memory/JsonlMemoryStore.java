// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.memory;

import io.jclaw.ports.memory.Embedding;
import io.jclaw.ports.memory.MemoryRecord;
import io.jclaw.ports.memory.MemoryRecord.MemoryId;
import io.jclaw.ports.memory.MemoryStore;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnScope;
import io.jclaw.adapter.out.persistence.rows.RowStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable {@link MemoryStore} over an append-only JSONL file.
 *
 * <p>Deletion is a tombstone record rather than a rewrite, keeping the file append-only like every
 * other log here. Current state is the fold: a memory exists until a {@code deleted} record for its
 * id appears, and carries the embedding from the latest {@code written} or {@code embedded} record.
 *
 * <p>Embeddings are stored inline as a JSON number array with the model that produced them. A
 * 768-dimensional vector is a few kilobytes per memory, which is fine at the scale a JSONL store
 * serves; a store facing millions of memories would index vectors separately, and the port leaves
 * room for that by keeping ranking outside the store.
 *
 * <p>Scope isolation is enforced on read. Memories are retrieved into prompts, so a leak across
 * scopes is a prompt-injection vector: content written in one project could steer a run in
 * another. The filter is applied here rather than trusted to callers.
 */
public final class JsonlMemoryStore implements MemoryStore {

    private static final String KIND_WRITTEN = "written";
    private static final String KIND_EMBEDDED = "embedded";
    private static final String KIND_DELETED = "deleted";

    private final RowStore file;
    private final Clock clock;

    public JsonlMemoryStore(RowStore file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MemoryId write(TurnScope scope, String text, List<String> tags, Optional<Embedding> embedding) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(embedding, "embedding");

        MemoryRecord record = new MemoryRecord(
                MemoryId.fresh(), scope, text, tags, clock.instant(), embedding);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_WRITTEN);
        row.put("id", record.id().value());
        row.put("tenant", scope.tenant());
        row.put("agent", scope.agent());
        row.put("project", scope.project());
        row.put("thread", scope.thread().value());
        row.put("text", record.text());
        row.put("tags", record.tags());
        row.put("createdAt", record.createdAt().toString());
        record.embedding().ifPresent(vector -> row.put("embedding", encode(vector)));
        file.append(row);
        return record.id();
    }

    @Override
    public boolean attachEmbedding(MemoryId id, Embedding embedding) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(embedding, "embedding");
        if (find(id).isEmpty()) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_EMBEDDED);
        row.put("id", id.value());
        row.put("embedding", encode(embedding));
        row.put("at", clock.instant().toString());
        file.append(row);
        return true;
    }

    @Override
    public Optional<MemoryRecord> find(MemoryId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(replay().get(id.value()));
    }

    @Override
    public List<MemoryRecord> all(TurnScope scope) {
        Objects.requireNonNull(scope, "scope");
        return replay().values().stream()
                // Scope isolation: never return another project's memories.
                .filter(record -> sameProject(record.scope(), scope))
                .sorted(Comparator.comparing(MemoryRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean delete(MemoryId id) {
        Objects.requireNonNull(id, "id");
        if (find(id).isEmpty()) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_DELETED);
        row.put("id", id.value());
        row.put("deletedAt", clock.instant().toString());
        file.append(row);
        return true;
    }

    @Override
    public int count(TurnScope scope) {
        return all(scope).size();
    }

    /**
     * Memories are shared across threads within a project.
     *
     * <p>A memory written while discussing one thread is still knowledge about the project, so
     * isolating per thread would make memory nearly useless. The boundary that matters for
     * injection is the project, and that is the one enforced.
     */
    private static boolean sameProject(TurnScope stored, TurnScope requested) {
        return stored.tenant().equals(requested.tenant())
                && stored.agent().equals(requested.agent())
                && stored.project().equals(requested.project());
    }

    private static Map<String, Object> encode(Embedding embedding) {
        float[] values = embedding.values();
        List<Double> numbers = new ArrayList<>(values.length);
        for (float value : values) {
            numbers.add((double) value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", embedding.model());
        out.put("values", numbers);
        return out;
    }

    private static Optional<Embedding> decode(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || !(map.get("values") instanceof List<?> numbers)) {
            return Optional.empty();
        }
        float[] values = new float[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            if (!(numbers.get(i) instanceof Number number)) {
                return Optional.empty();
            }
            values[i] = number.floatValue();
        }
        try {
            return Optional.of(new Embedding(String.valueOf(map.get("model")), values));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, MemoryRecord> replay() {
        Map<String, MemoryRecord> memories = new LinkedHashMap<>();
        for (Map<String, Object> row : file.readAll()) {
            try {
                String kind = String.valueOf(row.get("kind"));
                String id = String.valueOf(row.get("id"));
                if (KIND_DELETED.equals(kind)) {
                    memories.remove(id);
                    continue;
                }
                if (KIND_EMBEDDED.equals(kind)) {
                    MemoryRecord existing = memories.get(id);
                    if (existing != null) {
                        decode(row.get("embedding"))
                                .ifPresent(vector -> memories.put(id, existing.withEmbedding(vector)));
                    }
                    continue;
                }
                if (!KIND_WRITTEN.equals(kind)) {
                    continue;
                }
                List<String> tags = row.get("tags") instanceof List<?> raw
                        ? ((List<Object>) raw).stream().map(String::valueOf).toList()
                        : List.of();
                memories.put(id, new MemoryRecord(
                        new MemoryId(id),
                        new TurnScope(
                                String.valueOf(row.get("tenant")),
                                String.valueOf(row.get("agent")),
                                String.valueOf(row.get("project")),
                                new ThreadId(String.valueOf(row.get("thread")))),
                        String.valueOf(row.get("text")),
                        tags,
                        Instant.parse(String.valueOf(row.get("createdAt"))),
                        decode(row.get("embedding"))));
            } catch (RuntimeException e) {
                // A damaged line loses one memory, not the whole store.
            }
        }
        return memories;
    }
}
