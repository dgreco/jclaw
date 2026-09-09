package io.jclaw.storage.projection;

import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.projection.RunProjection.CapabilityCall;
import io.jclaw.domain.projection.RunProjection.Gate;
import io.jclaw.domain.projection.RunProjection.RunView;
import io.jclaw.storage.sql.SqlSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A projection is cached only when it can no longer change, which is what makes the cache free
 * of invalidation and therefore free of the bug class that comes with it.
 */
class RunProjectionCacheTest {

    private static final TurnRunId RUN = new TurnRunId("run_1");

    private static DataSource fresh() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        SqlSchema.migrate(ds);
        return ds;
    }

    private static RunView view(TurnStatus status) {
        return new RunView(
                RUN,
                Optional.of(TurnScope.local("p", new ThreadId("t"))),
                Optional.of(status),
                Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
                status.isTerminal() ? Optional.of(Instant.parse("2026-01-01T00:01:00Z")) : Optional.empty(),
                Optional.of(Instant.parse("2026-01-01T00:01:00Z")),
                3, 1,
                new Usage(100, 50, 10, 5),
                List.of(new CapabilityCall("builtin.shell", "PROCESS", "ok", 42)),
                List.of(new Gate(GateKind.APPROVAL, "gate_1", Optional.of(true))),
                2, 4, 5,
                status == TurnStatus.FAILED ? Optional.of(FailureKind.DRIVER_PROTOCOL_VIOLATION) : Optional.empty());
    }

    @Test
    @DisplayName("a finished run's projection round-trips through the database whole")
    void roundTrips() {
        var cache = new JdbcRunProjectionCache(fresh(), Clock.systemUTC());
        RunView stored = view(TurnStatus.COMPLETED);
        cache.put(stored);

        RunView read = cache.find(RUN).orElseThrow();
        assertEquals(stored, read, "every component survives the codec");
    }

    @Test
    @DisplayName("a failed run keeps its failure kind, which is the field most worth not losing")
    void keepsFailure() {
        var cache = new JdbcRunProjectionCache(fresh(), Clock.systemUTC());
        cache.put(view(TurnStatus.FAILED));
        assertEquals(Optional.of(FailureKind.DRIVER_PROTOCOL_VIOLATION),
                cache.find(RUN).orElseThrow().failure());
    }

    @Test
    @DisplayName("a run that has not finished is never stored")
    void onlyTerminalRunsAreCached() {
        var cache = new JdbcRunProjectionCache(fresh(), Clock.systemUTC());
        cache.put(view(TurnStatus.BLOCKED_APPROVAL));
        assertTrue(cache.find(RUN).isEmpty(),
                "a parked run's events can still change, so its fold must not be kept");

        cache.put(view(TurnStatus.RUNNING));
        assertTrue(cache.find(RUN).isEmpty());
    }

    @Test
    @DisplayName("the read-through folds on a miss, stores a finished run, and reads it next time")
    void readThrough() {
        var cache = new JdbcRunProjectionCache(fresh(), Clock.systemUTC());
        AtomicInteger folds = new AtomicInteger();
        List<JclawEvent> events = List.of(
                new JclawEvent.TurnSubmitted(Instant.parse("2026-01-01T00:00:00Z"), RUN,
                        TurnScope.local("p", new ThreadId("t"))),
                new JclawEvent.RunFinished(Instant.parse("2026-01-01T00:01:00Z"), RUN,
                        TurnStatus.COMPLETED, Optional.empty(), Usage.of(100, 50), 2));

        RunView first = cache.of(RUN, () -> {
            folds.incrementAndGet();
            return events;
        });
        assertEquals(1, folds.get());
        assertEquals(Optional.of(TurnStatus.COMPLETED), first.status());

        RunView second = cache.of(RUN, () -> {
            folds.incrementAndGet();
            return events;
        });
        assertEquals(1, folds.get(), "the second read did not touch the event log");
        assertEquals(first, second);
    }

    @Test
    @DisplayName("a row from another codec version is a miss, not a failure")
    void versionMismatchIsAMiss() {
        DataSource ds = fresh();
        new JdbcTemplate(ds).update(
                "INSERT INTO jclaw_run_projection (run, thread, status, updated_at, body) VALUES (?, ?, ?, ?, ?)",
                RUN.value(), "t", "COMPLETED", new java.sql.Timestamp(0),
                "{\"v\":999,\"run\":\"run_1\"}");
        assertTrue(new JdbcRunProjectionCache(ds, Clock.systemUTC()).find(RUN).isEmpty());

        new JdbcTemplate(ds).update("UPDATE jclaw_run_projection SET body = ? WHERE run = ?",
                "not json at all", RUN.value());
        assertTrue(new JdbcRunProjectionCache(ds, Clock.systemUTC()).find(RUN).isEmpty(),
                "an unreadable row must degrade to a fold, never to an exception");
    }

    @Test
    @DisplayName("the no-op cache keeps nothing, so JSONL behaves exactly as it did")
    void noneKeepsNothing() {
        RunProjectionCache none = RunProjectionCache.none();
        none.put(view(TurnStatus.COMPLETED));
        assertTrue(none.find(RUN).isEmpty());

        AtomicInteger folds = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            none.of(RUN, () -> {
                folds.incrementAndGet();
                return List.of();
            });
        }
        assertEquals(3, folds.get(), "every read folds, as before");
    }

    @Test
    @DisplayName("writing the same run twice replaces the row rather than failing on the key")
    void writeIsIdempotent() {
        var cache = new JdbcRunProjectionCache(fresh(), Clock.systemUTC());
        cache.put(view(TurnStatus.COMPLETED));
        cache.put(view(TurnStatus.FAILED));
        assertEquals(Optional.of(TurnStatus.FAILED), cache.find(RUN).orElseThrow().status());
        assertFalse(cache.find(RUN).orElseThrow().failure().isEmpty());
    }
}
