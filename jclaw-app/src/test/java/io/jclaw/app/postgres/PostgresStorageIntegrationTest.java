// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.postgres;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.ThreadLock;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.storage.lock.SqlThreadLock;
import io.jclaw.storage.projection.RunProjectionCache;
import io.jclaw.storage.sql.SqlSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jclaw against a real PostgreSQL, not embedded H2.
 *
 * <p>Everything under {@code storage=sql} — four migrations, a table per busy store, materialised
 * projections, the connection pool, the cross-host thread lock — had only ever run against H2.
 * {@link SqlSchema} claims its DDL is "written in the dialect H2 and PostgreSQL share", and
 * nothing checked that claim. This is the check. It is the sort of thing that passes for months
 * and then fails on the one database anybody deploys.
 *
 * <p>Skipped without Docker rather than failed: a developer with no daemon running should not see
 * a red suite for an environment problem. CI has one.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresStorageIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("jclaw")
            .withUsername("jclaw")
            .withPassword("jclaw-test-password");

    private static Path workspace;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        workspace = Files.createTempDirectory("jclaw-pg-it");
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.storage", () -> "sql");
        // The password is read from JCLAW_DATASOURCE_PASSWORD and from nowhere else — a URL is
        // topology, a credential is not, and there is deliberately no property for it. A test
        // cannot set an environment variable for its own JVM, so the credential rides in the URL
        // instead, which is the other shape a real deployment uses (`DATABASE_URL`).
        registry.add("jclaw.datasource-url", () -> {
            String url = POSTGRES.getJdbcUrl();
            return url + (url.indexOf('?') >= 0 ? "&" : "?") + "password=" + POSTGRES.getPassword();
        });
        registry.add("jclaw.datasource-username", POSTGRES::getUsername);
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("answered from postgres");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired ThreadService threads;
    @Autowired RunStore runs;
    @Autowired EventLog events;
    @Autowired SecretVault vault;
    @Autowired RunProjectionCache projections;
    @Autowired io.jclaw.app.config.StorageBackend backend;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(backend.dataSource().orElseThrow());
    }

    /** A second connection to the same database: what another host would open. */
    private static DataSource asAnotherHost() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("every migration applies to PostgreSQL and a turn round-trips through it")
    void migrationsApplyAndATurnRuns() {
        assertTrue(backend.isSql());
        assertEquals(Integer.valueOf(SqlSchema.currentVersion()),
                jdbc().queryForObject("SELECT MAX(version) FROM jclaw_schema", Integer.class),
                "the DDL is meant to be portable; this is the only thing that proves it");

        // Every table the migrations create, on the database that actually matters.
        for (String table : List.of("jclaw_rows", "jclaw_events", "jclaw_transcript", "jclaw_runs",
                "jclaw_checkpoints", "jclaw_results", "jclaw_approvals", "jclaw_memory",
                "jclaw_routines", "jclaw_run_projection", "jclaw_thread_locks")) {
            assertEquals(Integer.valueOf(1), jdbc().queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables"
                            + " WHERE table_name = ? AND table_schema = 'public'",
                    Integer.class, table),
                    () -> table + " should exist");
        }

        JclawRuntime.TurnResult result = runtime.submit(new ThreadId("pg"),
                ChatMessage.user("hello postgres"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, result.status(), () -> String.valueOf(result.failureDetail()));
        assertEquals(Optional.of("answered from postgres"), result.reply());

        // The identity columns PostgreSQL generates must order rows the way a RowStore promises.
        assertEquals(List.of("hello postgres", "answered from postgres"),
                threads.history(new ThreadId("pg"), 10).stream()
                        .map(m -> m.message().displayText()).toList(),
                "append order is the whole contract of a row store");
        assertTrue(runs.find(result.run()).isPresent());
        assertFalse(events.readRun(result.run()).isEmpty());
    }

    @Test
    @DisplayName("the busy stores use their own tables, and the small ones share")
    void perConceptTablesAreUsed() {
        runtime.submit(new ThreadId("pg-tables"), ChatMessage.user("write some rows"),
                new AtomicBoolean(false), Optional.empty());

        for (String table : List.of("jclaw_events", "jclaw_transcript", "jclaw_runs", "jclaw_checkpoints")) {
            Integer rows = jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertTrue(rows != null && rows > 0, table + " should hold rows, had " + rows);
        }
        List<String> shared = jdbc().queryForList(
                "SELECT DISTINCT store FROM jclaw_rows ORDER BY store", String.class);
        assertFalse(shared.contains("events"), "a busy store no longer shares: " + shared);
    }

    @Test
    @DisplayName("a finished run's projection is materialised and equals the fold")
    void projectionsAreMaterialised() {
        JclawRuntime.TurnResult result = runtime.submit(new ThreadId("pg-proj"),
                ChatMessage.user("project me"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, result.status());

        var cached = projections.find(result.run()).orElseThrow();
        assertEquals(Optional.of(TurnStatus.COMPLETED), cached.status());
        assertEquals(io.jclaw.domain.projection.RunProjection.fold(result.run(),
                        events.readRun(result.run()).stream().map(EventLog.Entry::event).toList()),
                cached, "the materialised row is the fold, not an approximation");
    }

    @Test
    @DisplayName("two hosts contend for a thread through the database")
    void threadLockSpansHosts() {
        TurnScope scope = TurnScope.local("pg-project", new ThreadId("pg-contended"));
        try (SqlThreadLock alpha = new SqlThreadLock(asAnotherHost(), "host-a",
                     Duration.ofMinutes(5), Clock.systemUTC());
             SqlThreadLock beta = new SqlThreadLock(asAnotherHost(), "host-b",
                     Duration.ofMinutes(5), Clock.systemUTC())) {

            Optional<ThreadLock.Held> first = alpha.tryAcquire(scope);
            assertTrue(first.isPresent());
            assertTrue(beta.tryAcquire(scope).isEmpty(),
                    "PostgreSQL decides the race through the primary key, as H2 does");

            first.orElseThrow().close();
            Optional<ThreadLock.Held> second = beta.tryAcquire(scope);
            assertTrue(second.isPresent());
            second.orElseThrow().close();
        }
    }

    @Test
    @DisplayName("the vault is encrypted in PostgreSQL too")
    void vaultStaysEncrypted() {
        vault.put(new SecretVault.SecretName("pg-secret"), "value-that-must-not-appear",
                new SecretVault.Binding(CapabilityId.of("builtin.http_fetch"), Set.of("api.example.com")));
        assertEquals("value-that-must-not-appear",
                vault.lease(new SecretVault.SecretName("pg-secret")).orElseThrow().value());

        List<String> bodies = jdbc().queryForList(
                "SELECT body FROM jclaw_rows WHERE store = 'secrets'", String.class);
        assertFalse(bodies.isEmpty(), "the secret was stored somewhere");
        assertTrue(bodies.stream().noneMatch(body -> body.contains("value-that-must-not-appear")),
                "a database an operator can read must not hand them the credential");
    }
}
