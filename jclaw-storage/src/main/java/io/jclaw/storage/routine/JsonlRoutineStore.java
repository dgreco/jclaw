// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.routine;

import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.storage.rows.RowStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable {@link RoutineStore} over an append-only JSONL file.
 *
 * <p>Same fold-the-log shape as the other stores: creation writes one record, and each subsequent
 * change (enable, pause, firing) appends another. Firings in particular are worth keeping rather
 * than overwriting — "when did this routine actually run" is the first question asked when a
 * scheduled job misbehaves.
 */
public final class JsonlRoutineStore implements RoutineStore {

    private static final String KIND_CREATED = "created";
    private static final String KIND_ENABLED = "enabled";
    private static final String KIND_FIRED = "fired";
    private static final String KIND_DELETED = "deleted";

    private final RowStore file;
    private final Clock clock;

    public JsonlRoutineStore(RowStore file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Routine create(
            TurnScope scope, String name, String trigger, String zone,
            String prompt, ThreadId thread) {

        Routine routine = new Routine(
                RoutineId.fresh(), scope, name, trigger, zone, prompt, thread,
                true, Optional.empty(), clock.instant());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_CREATED);
        row.put("id", routine.id().value());
        row.put("tenant", scope.tenant());
        row.put("agent", scope.agent());
        row.put("project", scope.project());
        row.put("scopeThread", scope.thread().value());
        row.put("name", name);
        row.put("cron", trigger);
        row.put("zone", zone);
        row.put("prompt", prompt);
        row.put("thread", thread.value());
        row.put("createdAt", routine.createdAt().toString());
        file.append(row);
        return routine;
    }

    @Override
    public Optional<Routine> find(RoutineId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(replay().get(id.value()));
    }

    @Override
    public List<Routine> list(TurnScope scope) {
        Objects.requireNonNull(scope, "scope");
        return replay().values().stream()
                .filter(routine -> sameProject(routine.scope(), scope))
                .sorted(Comparator.comparing(Routine::createdAt))
                .toList();
    }

    @Override
    public boolean setEnabled(RoutineId id, boolean enabled) {
        if (find(id).isEmpty()) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_ENABLED);
        row.put("id", id.value());
        row.put("enabled", enabled);
        row.put("at", clock.instant().toString());
        file.append(row);
        return true;
    }

    @Override
    public void recordFiring(RoutineId id, Instant firedAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firedAt, "firedAt");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_FIRED);
        row.put("id", id.value());
        row.put("firedAt", firedAt.toString());
        file.append(row);
    }

    @Override
    public boolean delete(RoutineId id) {
        if (find(id).isEmpty()) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_DELETED);
        row.put("id", id.value());
        row.put("at", clock.instant().toString());
        file.append(row);
        return true;
    }

    /** Routines are project-scoped, matching how memories are scoped. */
    private static boolean sameProject(TurnScope stored, TurnScope requested) {
        return stored.tenant().equals(requested.tenant())
                && stored.agent().equals(requested.agent())
                && stored.project().equals(requested.project());
    }

    private Map<String, Routine> replay() {
        Map<String, Routine> routines = new LinkedHashMap<>();
        for (Map<String, Object> row : file.readAll()) {
            try {
                String kind = String.valueOf(row.get("kind"));
                String id = String.valueOf(row.get("id"));
                switch (kind) {
                    case KIND_CREATED -> routines.put(id, toRoutine(row, id));
                    case KIND_DELETED -> routines.remove(id);
                    case KIND_ENABLED -> {
                        Routine existing = routines.get(id);
                        if (existing != null) {
                            routines.put(id, existing.withEnabled(
                                    row.get("enabled") instanceof Boolean flag && flag));
                        }
                    }
                    case KIND_FIRED -> {
                        Routine existing = routines.get(id);
                        if (existing != null) {
                            routines.put(id, existing.withLastFiredAt(
                                    Instant.parse(String.valueOf(row.get("firedAt")))));
                        }
                    }
                    default -> { /* forward compatibility: unknown kinds are ignored */ }
                }
            } catch (RuntimeException e) {
                // A damaged line loses one routine, not the schedule.
            }
        }
        return routines;
    }

    private static Routine toRoutine(Map<String, Object> row, String id) {
        return new Routine(
                new RoutineId(id),
                new TurnScope(
                        String.valueOf(row.get("tenant")),
                        String.valueOf(row.get("agent")),
                        String.valueOf(row.get("project")),
                        new ThreadId(String.valueOf(row.get("scopeThread")))),
                String.valueOf(row.get("name")),
                String.valueOf(row.get("cron")),
                String.valueOf(row.get("zone")),
                String.valueOf(row.get("prompt")),
                new ThreadId(String.valueOf(row.get("thread"))),
                true,
                Optional.empty(),
                Instant.parse(String.valueOf(row.get("createdAt"))));
    }
}
