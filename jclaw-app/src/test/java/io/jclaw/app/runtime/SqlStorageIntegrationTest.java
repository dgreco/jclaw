package io.jclaw.app.runtime;

import io.jclaw.app.config.StorageBackend;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.providers.mock.MockModelProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With {@code jclaw.storage=sql}, a turn leaves its rows in the database and no JSONL behind. */
@SpringBootTest
class SqlStorageIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-sql-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.storage", () -> "sql");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("stored in sql");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired ThreadService threads;
    @Autowired RunStore runs;
    @Autowired SecretVault vault;
    @Autowired StorageBackend backend;
    @Autowired RetentionService retention;

    @Test
    @DisplayName("a turn's rows land in jclaw_rows, the stores read them back, and nothing is written as JSONL")
    void persistsToSql() throws IOException {
        assertTrue(backend.isSql());
        ThreadId thread = new ThreadId("sql");
        JclawRuntime.TurnResult result = runtime.submit(thread, ChatMessage.user("hello"),
                new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(Optional.of("stored in sql"), result.reply());

        assertEquals(List.of("hello", "stored in sql"),
                threads.history(thread, 10).stream().map(m -> m.message().displayText()).toList());
        assertTrue(runs.find(result.run()).isPresent());

        vault.put(new SecretVault.SecretName("k"), "value-0123456789",
                new SecretVault.Binding(CapabilityId.of("builtin.http_fetch"), Set.of("api.example.com")));
        assertEquals("value-0123456789", vault.lease(new SecretVault.SecretName("k")).orElseThrow().value());

        JdbcTemplate jdbc = new JdbcTemplate(backend.dataSource().orElseThrow());
        List<String> stores = jdbc.queryForList("SELECT DISTINCT store FROM jclaw_rows ORDER BY store", String.class);
        assertTrue(stores.containsAll(List.of("events", "transcript", "runs", "checkpoints", "secrets")), stores.toString());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM jclaw_schema", Integer.class));

        try (var files = Files.list(workspace.resolve(".state"))) {
            List<String> names = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jsonl")).toList();
            assertTrue(names.isEmpty(), "no JSONL store files in sql mode: " + names);
        }
        String body = jdbc.queryForList("SELECT body FROM jclaw_rows WHERE store = 'secrets'", String.class).get(0);
        assertFalse(body.contains("value-0123456789"), "the vault is encrypted in SQL too");

        assertEquals(3, retention.sweep(true).size(), "retention sweeps the SQL stores");
    }
}
