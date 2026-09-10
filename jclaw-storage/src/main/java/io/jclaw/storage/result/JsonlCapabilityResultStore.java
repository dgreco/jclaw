// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.result;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.CapabilityResultStore;
import io.jclaw.contracts.turn.Ident;
import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.storage.rows.RowStore;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable {@link CapabilityResultStore} over an append-only JSONL file.
 *
 * <p>Result refs are evidence: a {@code LoopExit.Completed} carries the refs of every capability
 * result the run produced, and the runtime re-resolves them before trusting the exit. Evidence
 * that evaporates with the process is useless for the case it exists for. A run that parked on a
 * gate in one process and resumed in another completes with refs minted by the first process, so
 * the store they resolve against has to outlive it.
 *
 * <p>Payloads arrive already redacted and bounded by the kernel (64 KiB by default), so the file
 * grows by at most that per capability call. It is never rewritten: like every other store here,
 * a line is appended and the current state is the fold. Nothing deletes results; they are the
 * audit trail behind the {@code capability.invoked} events, which carry only the fingerprint.
 *
 * <p>Sole minter of {@link LoopResultRef}. Refs are unguessable so a driver cannot fabricate one
 * that resolves; the security property holds because resolution is a lookup here, not a format
 * check.
 */
public final class JsonlCapabilityResultStore implements CapabilityResultStore {

    private static final String KIND_RESULT = "result";

    private final RowStore file;
    private final Clock clock;

    public JsonlCapabilityResultStore(RowStore file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public LoopResultRef store(
            TurnRunId run, CapabilityInvocation invocation, String payload, boolean truncated) {

        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(payload, "payload");

        LoopResultRef ref = new LoopResultRef(Ident.fresh("res"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_RESULT);
        row.put("ref", ref.value());
        row.put("run", run.value());
        row.put("capability", invocation.capability().value());
        row.put("fingerprint", invocation.fingerprint());
        row.put("payload", payload);
        row.put("truncated", truncated);
        row.put("storedAt", clock.instant().toString());
        file.append(row);
        return ref;
    }

    @Override
    public Optional<StoredResult> resolve(LoopResultRef ref) {
        Objects.requireNonNull(ref, "ref");
        for (Map<String, Object> row : file.readAll()) {
            try {
                if (KIND_RESULT.equals(String.valueOf(row.get("kind")))
                        && ref.value().equals(String.valueOf(row.get("ref")))) {
                    return Optional.of(new StoredResult(
                            ref,
                            new TurnRunId(String.valueOf(row.get("run"))),
                            CapabilityId.of(String.valueOf(row.get("capability"))),
                            String.valueOf(row.get("fingerprint")),
                            String.valueOf(row.get("payload")),
                            row.get("truncated") instanceof Boolean b && b,
                            Instant.parse(String.valueOf(row.get("storedAt")))));
                }
            } catch (RuntimeException e) {
                // A damaged line loses one result, not the store.
            }
        }
        return Optional.empty();
    }

    /** Number of stored results. Exposed for diagnostics and tests. */
    public int size() {
        return file.size();
    }
}
