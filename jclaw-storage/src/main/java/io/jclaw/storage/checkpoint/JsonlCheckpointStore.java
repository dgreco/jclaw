package io.jclaw.storage.checkpoint;

import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.CheckpointStore;
import io.jclaw.contracts.turn.Ident;
import io.jclaw.contracts.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.storage.rows.RowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable {@link CheckpointStore} over an append-only JSONL file.
 *
 * <p>Needed for the same reason the approval store is: a run that parks on a gate exits, and the
 * process that resumes it later is a different one. A checkpoint only in memory means a blocked
 * run can never be resumed, only restarted — which is exactly the duplicated side effect
 * checkpointing exists to prevent.
 *
 * <p>Payloads are base64-encoded. The store treats them as opaque bytes and never parses them:
 * {@link CheckpointKind} and the schema version are store-side metadata precisely so lease
 * recovery can judge replay safety without understanding loop-owned state it may not be able to
 * deserialize after an upgrade.
 */
public final class JsonlCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(JsonlCheckpointStore.class);

    private final RowStore file;
    private final Clock clock;

    public JsonlCheckpointStore(RowStore file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public LoopCheckpointStateRef write(
            TurnRunId run, CheckpointKind kind, int iteration, int schemaVersion, byte[] payload) {

        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");

        LoopCheckpointStateRef ref = new LoopCheckpointStateRef(Ident.fresh("ckpt"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ref", ref.value());
        record.put("run", run.value());
        record.put("kind", kind.name());
        record.put("iteration", iteration);
        record.put("schemaVersion", schemaVersion);
        record.put("payload", Base64.getEncoder().encodeToString(payload));
        record.put("writtenAt", clock.instant().toString());
        file.append(record);
        log.trace("checkpoint {} persisted (run {}, kind {}, iteration {}, {} bytes)",
                ref.value(), run.value(), kind, iteration, payload.length);
        return ref;
    }

    @Override
    public Optional<Checkpoint> resolve(LoopCheckpointStateRef ref) {
        Objects.requireNonNull(ref, "ref");
        return all().stream()
                .filter(checkpoint -> checkpoint.ref().equals(ref))
                .findFirst();
    }

    @Override
    public Optional<Checkpoint> latestFor(TurnRunId run) {
        Objects.requireNonNull(run, "run");
        return all().stream()
                .filter(checkpoint -> checkpoint.run().equals(run))
                .max(Comparator.comparing(Checkpoint::writtenAt));
    }

    private List<Checkpoint> all() {
        return file.readAll().stream()
                .map(JsonlCheckpointStore::decode)
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<Checkpoint> decode(Map<String, Object> record) {
        try {
            return Optional.of(new Checkpoint(
                    new LoopCheckpointStateRef(String.valueOf(record.get("ref"))),
                    new TurnRunId(String.valueOf(record.get("run"))),
                    CheckpointKind.valueOf(String.valueOf(record.get("kind"))),
                    intOf(record.get("iteration")),
                    intOf(record.get("schemaVersion")),
                    Base64.getDecoder().decode(String.valueOf(record.get("payload"))),
                    Instant.parse(String.valueOf(record.get("writtenAt")))));
        } catch (RuntimeException e) {
            // An unreadable checkpoint must not be silently treated as replay-safe; dropping it
            // means recovery sees no checkpoint and fails closed, which is the safe direction.
            return Optional.empty();
        }
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
