// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.inbound;

import io.jclaw.ports.inbound.InboundReviewStore;
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
import java.util.UUID;

/**
 * Held inbound messages as rows, replayed the way every other store here replays: append-only,
 * last row per id wins.
 *
 * <p>The text is stored as it arrived, unfenced. A reviewer has to see what was actually sent —
 * showing them the framed copy would hide the thing they are being asked to judge — and the
 * fencing is applied when an approval turns the message into a turn.
 */
public final class JsonlInboundReviewStore implements InboundReviewStore {

    private static final String KIND_HELD = "held";
    private static final String KIND_DECIDED = "decided";

    private final RowStore file;
    private final Clock clock;

    public JsonlInboundReviewStore(RowStore file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized Held hold(String source, TurnScope scope, ThreadId thread, String text,
            String severity, List<String> rules) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(thread, "thread");

        Held held = new Held("inb_" + UUID.randomUUID().toString().replace("-", ""),
                source, scope, thread, text, severity, rules,
                State.HELD, clock.instant(), Optional.empty());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_HELD);
        row.put("id", held.id());
        row.put("source", held.source());
        row.put("tenant", scope.tenant());
        row.put("agent", scope.agent());
        row.put("project", scope.project());
        row.put("thread", thread.value());
        row.put("text", held.text());
        row.put("severity", held.severity());
        row.put("rules", held.rules());
        row.put("receivedAt", held.receivedAt().toString());
        file.append(row);
        return held;
    }

    @Override
    public synchronized List<Held> pending(TurnScope scope) {
        Objects.requireNonNull(scope, "scope");
        // Tenant, not the whole scope: a reviewer looks at everything held for their tenant, not
        // only what happened to arrive on one thread.
        return replay().values().stream()
                .filter(Held::isPending)
                .filter(held -> held.scope().tenant().equals(scope.tenant()))
                .sorted(Comparator.comparing(Held::receivedAt))
                .toList();
    }

    @Override
    public synchronized Optional<Held> find(String id) {
        return Optional.ofNullable(replay().get(id));
    }

    @Override
    public synchronized Optional<Held> decide(String id, boolean approved, Instant at) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        Held held = replay().get(id);
        // Not pending means somebody already decided it. Returning empty rather than overwriting
        // is what stops two reviewers both approving one message into two turns.
        if (held == null || !held.isPending()) {
            return Optional.empty();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_DECIDED);
        row.put("id", id);
        row.put("approved", approved);
        row.put("decidedAt", at.toString());
        file.append(row);
        return Optional.of(new Held(held.id(), held.source(), held.scope(), held.thread(),
                held.text(), held.severity(), held.rules(),
                approved ? State.APPROVED : State.DISCARDED, held.receivedAt(), Optional.of(at)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Held> replay() {
        Map<String, Held> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : file.readAll()) {
            try {
                String id = String.valueOf(row.get("id"));
                switch (String.valueOf(row.get("kind"))) {
                    case KIND_HELD -> {
                        List<String> rules = new ArrayList<>();
                        if (row.get("rules") instanceof List<?> raw) {
                            raw.forEach(rule -> rules.add(String.valueOf(rule)));
                        }
                        byId.put(id, new Held(
                                id,
                                text(row, "source"),
                                new TurnScope(text(row, "tenant"), text(row, "agent"),
                                        text(row, "project"), new ThreadId(text(row, "thread"))),
                                new ThreadId(text(row, "thread")),
                                text(row, "text"),
                                text(row, "severity"),
                                rules,
                                State.HELD,
                                Instant.parse(text(row, "receivedAt")),
                                Optional.empty()));
                    }
                    case KIND_DECIDED -> {
                        Held existing = byId.get(id);
                        if (existing != null) {
                            boolean approved = row.get("approved") instanceof Boolean flag && flag;
                            byId.put(id, new Held(existing.id(), existing.source(), existing.scope(),
                                    existing.thread(), existing.text(), existing.severity(),
                                    existing.rules(),
                                    approved ? State.APPROVED : State.DISCARDED,
                                    existing.receivedAt(),
                                    Optional.of(Instant.parse(text(row, "decidedAt")))));
                        }
                    }
                    default -> { /* forward compatibility */ }
                }
            } catch (RuntimeException e) {
                // One damaged row costs that row, as everywhere else here.
            }
        }
        return byId;
    }

    private static String text(Map<String, Object> row, String key) {
        return row.get(key) instanceof String value ? value : "";
    }
}
