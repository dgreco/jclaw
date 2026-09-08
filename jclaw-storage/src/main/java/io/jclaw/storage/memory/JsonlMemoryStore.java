package io.jclaw.storage.memory;

import io.jclaw.contracts.memory.MemoryRecord;
import io.jclaw.contracts.memory.MemoryRecord.MemoryId;
import io.jclaw.contracts.memory.MemoryStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.storage.jsonl.JsonlFile;

import java.time.Clock;
import java.time.Instant;
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
 * id appears.
 *
 * <p>Scope isolation is enforced on read. Memories are retrieved into prompts, so a leak across
 * scopes is a prompt-injection vector — content written in one project could steer a run in
 * another. The filter is applied here rather than trusted to callers.
 */
public final class JsonlMemoryStore implements MemoryStore {

    private static final String KIND_WRITTEN = "written";
    private static final String KIND_DELETED = "deleted";

    private final JsonlFile file;
    private final Clock clock;

    public JsonlMemoryStore(JsonlFile file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MemoryId write(TurnScope scope, String text, List<String> tags) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(tags, "tags");

        MemoryRecord record = new MemoryRecord(
                MemoryId.fresh(), scope, text, tags, clock.instant());

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
        file.append(row);
        return record.id();
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
                        Instant.parse(String.valueOf(row.get("createdAt")))));
            } catch (RuntimeException e) {
                // A damaged line loses one memory, not the whole store.
            }
        }
        return memories;
    }
}
