// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.domain.trigger.Trigger;
import io.jclaw.domain.trigger.WatchState;
import io.jclaw.kernel.guard.WorkspaceGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires {@code watch} routines when the files they match have changed.
 *
 * <p>Polled on the worker's tick rather than registered with the OS. {@code WatchService} is
 * per-directory, needs recursive registration that a build can invalidate halfway through a walk,
 * and on macOS falls back to polling anyway — so this polls openly, and the cost of that honesty
 * is a latency of one tick rather than a trigger that behaves differently on each platform.
 *
 * <p>The first look never fires. A watch created against an existing tree would otherwise fire
 * immediately on nothing having happened, which is how an operator learns to ignore it.
 *
 * <p>Fingerprints are <strong>durable</strong>, in a row store beside the other state. They were
 * in memory first, and that was wrong for the mode people actually deploy: {@code worker --once}
 * is a fresh process every tick, so an in-memory baseline meant the first look was always the
 * only look and a watch never fired at all. Persisting them makes a watch work the same under a
 * long-lived worker and under cron.
 *
 * <p>A worker that was down still misses nothing: the fingerprint it left behind is compared
 * against the tree as it is now, so changes made during the outage fire on the next tick. What
 * is lost is granularity — several changes while nobody was looking are one firing — which is
 * the honest behaviour for a poller.
 */
public class WatchTriggerScanner {

    private static final Logger log = LoggerFactory.getLogger(WatchTriggerScanner.class);

    /** Enough for a source tree; a walk that hits this is a watch pointed at the wrong place. */
    private static final int MAX_FILES = 20_000;

    /** Directory names never walked. Cheap, and they are where the churn is. */
    private static final java.util.Set<String> SKIP =
            java.util.Set.of(".git", "target", "build", "node_modules", ".jclaw", ".state");

    private final RoutineStore routines;
    private final JclawRuntime runtime;
    private final Path workspace;
    private final Clock clock;
    private final io.jclaw.storage.rows.RowStore store;
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public WatchTriggerScanner(RoutineStore routines, JclawRuntime runtime,
            WorkspaceGuard workspace, io.jclaw.storage.rows.RowStore watchState, Clock clock) {
        this.routines = Objects.requireNonNull(routines, "routines");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.workspace = Objects.requireNonNull(workspace, "workspace").root();
        this.store = Objects.requireNonNull(watchState, "watchState");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Replays the stored fingerprints once, on the first scan of the process. */
    private void load() {
        if (loaded) {
            return;
        }
        synchronized (fingerprints) {
            if (loaded) {
                return;
            }
            // Last row per routine wins, exactly as every other replayed store here works.
            for (Map<String, Object> row : store.readAll()) {
                if (row.get("routine") instanceof String routine
                        && row.get("fingerprint") instanceof String fingerprint) {
                    fingerprints.put(routine, fingerprint);
                }
            }
            loaded = true;
        }
    }

    private void remember(String routine, String fingerprint) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("routine", routine);
        row.put("fingerprint", fingerprint);
        row.put("at", clock.instant().toString());
        store.append(row);
    }

    /** One routine fired by a file change. */
    public record Fired(RoutineStore.Routine routine, String glob, io.jclaw.contracts.turn.TurnRunId run) { }

    /**
     * Checks every enabled watch routine and enqueues the ones whose tree changed.
     *
     * @return what fired, empty on the common tick where nothing did
     */
    public List<Fired> scan() {
        load();
        List<Fired> fired = new ArrayList<>();
        for (RoutineStore.Routine routine : routines.list(runtime.scopeFor(new ThreadId("routines")))) {
            if (!routine.enabled()) {
                continue;
            }
            if (!(Trigger.parse(routine.trigger()).toOptional().orElse(null) instanceof Trigger.Watch watch)) {
                continue;
            }
            String current;
            try {
                current = WatchState.fingerprint(scanGlob(watch.glob()));
            } catch (IOException | RuntimeException e) {
                log.debug("watch: {} could not be scanned ({})", routine.id().value(), e.toString());
                continue;
            }
            String key = routine.id().value();
            Optional<String> previous = Optional.ofNullable(fingerprints.put(key, current));
            if (previous.isEmpty() || !previous.get().equals(current)) {
                // Persist the baseline as well as a change: the first look must be durable, or a
                // `worker --once` deployment re-baselines every tick and never fires.
                remember(key, current);
            }
            if (!WatchState.changed(previous, current)) {
                continue;
            }
            routines.recordFiring(routine.id(), clock.instant());
            var run = runtime.enqueue(routine.thread(), ChatMessage.user(
                    routine.prompt() + "\n\nTriggering change: files matching '" + watch.glob()
                            + "' under the workspace changed."));
            log.debug("watch: {} fired on {} -> run {}", key, watch.glob(), run.value());
            fired.add(new Fired(routine, watch.glob(), run));
        }
        return fired;
    }

    /**
     * Modification times of everything under the workspace matching {@code glob}.
     *
     * <p>Matched against two patterns, not one. {@code java.nio}'s glob requires
     * {@code src/**}{@code /*.java} to have an intermediate directory, so it does not match
     * {@code src/One.java} — where git, ant, and ripgrep all do. That is the single most common
     * pattern an operator writes, and a watch that silently ignores the file next to the one it
     * matches is worse than no watch. So the {@code /**}{@code /} is also tried collapsed to
     * {@code /}, which makes the two readings agree.
     */
    private Map<String, Long> scanGlob(String glob) throws IOException {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        PathMatcher collapsed = glob.contains("/**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob.replace("/**/", "/"))
                : matcher;
        Map<String, Long> times = new LinkedHashMap<>();
        try (var walk = Files.walk(workspace)) {
            walk.filter(path -> {
                        // Skip the noisy directories wholesale rather than matching inside them.
                        for (Path part : workspace.relativize(path)) {
                            if (SKIP.contains(part.toString())) {
                                return false;
                            }
                        }
                        return Files.isRegularFile(path);
                    })
                    .limit(MAX_FILES)
                    .forEach(path -> {
                        Path relative = workspace.relativize(path);
                        if (!matcher.matches(relative) && !collapsed.matches(relative)) {
                            return;
                        }
                        try {
                            times.put(relative.toString().replace('\\', '/'),
                                    Files.getLastModifiedTime(path).toMillis());
                        } catch (IOException e) {
                            // A file that vanished between the walk and the stat is a change in
                            // itself; leaving it out of the fingerprint records that.
                        }
                    });
        }
        return times;
    }
}
