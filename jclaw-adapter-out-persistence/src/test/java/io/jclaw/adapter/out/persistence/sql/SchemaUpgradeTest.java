// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database written by an older build is brought forward without losing a row or reordering one.
 *
 * <p>This is the migration that matters: version 2 moves eight stores out of the shared table,
 * and a move that dropped rows or shuffled them would corrupt a transcript rather than fail
 * loudly. Order is the whole contract of a {@code RowStore}.
 */
class SchemaUpgradeTest {

    private static DataSource fresh() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** Builds a database at schema version 1 and fills it the way that build would have. */
    private static DataSource atVersion1() {
        DataSource ds = fresh();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE jclaw_schema ("
                + " version INT PRIMARY KEY, description VARCHAR(200) NOT NULL, applied_at TIMESTAMP NOT NULL)");
        for (String statement : SqlSchema.MIGRATIONS.get(0).statements()) {
            jdbc.execute(statement);
        }
        jdbc.update("INSERT INTO jclaw_schema (version, description, applied_at) VALUES (?, ?, ?)",
                1, "rows table", Timestamp.from(Instant.EPOCH));
        return ds;
    }

    @Test
    @DisplayName("version 2 moves a busy store's rows into its own table, in order and complete")
    void movesRowsPreservingOrder() {
        DataSource ds = atVersion1();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        for (int i = 1; i <= 50; i++) {
            jdbc.update("INSERT INTO jclaw_rows (store, run, thread, body) VALUES (?, ?, ?, ?)",
                    "events", "run_" + i, "t", "{\"n\":" + i + "}");
        }
        jdbc.update("INSERT INTO jclaw_rows (store, run, thread, body) VALUES (?, ?, ?, ?)",
                "mcp", null, null, "{\"server\":\"kept\"}");

        assertEquals(1, SqlSchema.migrate(ds), "the database was at version 1");

        JdbcRowStore events = new JdbcRowStore(ds, "events");
        List<Map<String, Object>> rows = events.readAll();
        assertEquals(50, rows.size(), "no row was lost in the move");
        for (int i = 0; i < 50; i++) {
            assertEquals(i + 1, ((Number) rows.get(i).get("n")).intValue(), "append order survived");
        }
        assertEquals("run_50", jdbc.queryForObject(
                "SELECT run FROM jclaw_events ORDER BY seq DESC LIMIT 1", String.class),
                "the lifted columns came across too");

        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM jclaw_rows WHERE store = 'events'", Integer.class),
                "the shared table no longer holds them");
        assertEquals(List.of("kept"), new JdbcRowStore(ds, "mcp").readAll().stream()
                        .map(row -> row.get("server")).toList(),
                "a store that keeps sharing the table is untouched");
    }

    @Test
    @DisplayName("every dedicated store gets a table, and the ones that share keep sharing")
    void everyDedicatedStoreHasATable() {
        DataSource ds = fresh();
        SqlSchema.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        SqlSchema.DEDICATED_TABLES.forEach((store, table) -> {
            assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class),
                    table + " must exist");
            assertEquals(table, SqlSchema.tableFor(store));
            assertTrue(!SqlSchema.isShared(store));
        });
        assertEquals("jclaw_rows", SqlSchema.tableFor("secrets"));
        assertEquals("jclaw_rows", SqlSchema.tableFor("secrets_acme"),
                "a per-tenant vault's name is not known when the migration is written");
        assertTrue(SqlSchema.isShared("sessions"));
    }

    @Test
    @DisplayName("upgrading twice is a no-op, and the migration is idempotent from any version")
    void upgradeIsIdempotent() {
        DataSource ds = atVersion1();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO jclaw_rows (store, run, thread, body) VALUES (?, ?, ?, ?)",
                "runs", "run_1", "t", "{\"n\":1}");

        assertEquals(1, SqlSchema.migrate(ds));
        assertEquals(SqlSchema.currentVersion(), SqlSchema.migrate(ds), "already current");
        assertEquals(1, new JdbcRowStore(ds, "runs").size(), "the row was moved once, not twice");
    }
}
