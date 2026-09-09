package io.jclaw.storage.sql;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.storage.event.JsonlEventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRowStoreTest {

    private static DataSource fresh() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        return ds;
    }

    @Test
    @DisplayName("migrations apply once, record their version, and refuse a newer database")
    void migrates() {
        DataSource ds = fresh();
        assertEquals(0, SqlSchema.migrate(ds), "a fresh database starts at version 0");
        assertEquals(SqlSchema.currentVersion(), SqlSchema.migrate(ds), "a second start finds it current");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertEquals(Integer.valueOf(SqlSchema.MIGRATIONS.size()),
                jdbc.queryForObject("SELECT COUNT(*) FROM jclaw_schema", Integer.class));

        jdbc.update("INSERT INTO jclaw_schema (version, description, applied_at) VALUES (?, ?, ?)",
                SqlSchema.currentVersion() + 1, "from the future", new java.sql.Timestamp(0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> SqlSchema.migrate(ds));
    }

    @Test
    @DisplayName("rows keep append order per store, rewrite replaces them, and stores are separate")
    void appendsAndRewrites() {
        DataSource ds = fresh();
        SqlSchema.migrate(ds);
        JdbcRowStore events = new JdbcRowStore(ds, "events");
        JdbcRowStore runs = new JdbcRowStore(ds, "runs");

        events.append(Map.of("n", 1, "run", "run_a"));
        events.append(Map.of("n", 2, "run", "run_b", "nested", Map.of("k", List.of("v"))));
        runs.append(Map.of("n", 9));

        assertEquals(List.of(1, 2), events.readAll().stream().map(r -> r.get("n")).toList());
        assertEquals(Map.of("k", List.of("v")), events.readAll().get(1).get("nested"));
        assertEquals(1, runs.size());
        assertEquals("run_b", new JdbcTemplate(ds).queryForObject(
                "SELECT run FROM jclaw_events ORDER BY seq DESC LIMIT 1", String.class),
                "the run column is lifted for indexing");
        assertEquals("jclaw_events", events.table());
        assertEquals("jclaw_rows", new JdbcRowStore(ds, "mcp").table(),
                "a small configuration store still shares the one table");
        assertEquals(0, new JdbcTemplate(ds).queryForObject(
                "SELECT COUNT(*) FROM jclaw_rows", Integer.class),
                "a busy store's rows are in its own table, not the shared one");

        events.rewrite(List.of(Map.of("n", 2)));
        assertEquals(List.of(2), events.readAll().stream().map(r -> r.get("n")).toList());
        assertEquals(1, runs.size(), "rewriting one store leaves another alone");
    }

    @Test
    @DisplayName("a store class runs unchanged over SQL: the event log's cursors are row positions")
    void storeOverSql() {
        DataSource ds = fresh();
        SqlSchema.migrate(ds);
        JsonlEventLog log = new JsonlEventLog(new JdbcRowStore(ds, "events"));
        TurnRunId run = new TurnRunId("run_1");
        TurnScope scope = TurnScope.local("p", new ThreadId("t"));

        log.append(new JclawEvent.TurnSubmitted(Instant.parse("2026-01-01T00:00:00Z"), run, scope));
        log.append(new JclawEvent.RunClaimed(Instant.parse("2026-01-01T00:00:01Z"), run, "w", Instant.parse("2026-01-01T00:01:00Z")));

        List<EventLog.Entry> entries = log.readRun(run);
        assertEquals(2, entries.size());
        assertTrue(entries.get(1).event() instanceof JclawEvent.RunClaimed);
        assertEquals(2, log.latest().position());
    }
}
