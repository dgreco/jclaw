// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.providers.mock.MockModelProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A file change under the workspace fires the routine watching it — and only then. */
@SpringBootTest
class WatchTriggerIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-watch-it");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/One.java"), "class One {}");
        Files.writeString(workspace.resolve("notes.md"), "unrelated");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("noticed");
        }
    }

    @Autowired WatchTriggerScanner scanner;
    @Autowired RoutineStore routines;
    @Autowired JclawRuntime runtime;
    @Autowired ThreadService threads;

    /**
     * What the scan fired, among the routines this test created.
     *
     * <p>The store is shared across methods in one Spring context and JUnit promises no order,
     * so a test that asserted on every routine would depend on which ran first.
     */
    private List<String> scanFor(String... mine) {
        java.util.Set<String> wanted = java.util.Set.of(mine);
        return scanner.scan().stream().map(f -> f.routine().name())
                .filter(wanted::contains).sorted().toList();
    }

    private void addWatch(String name, String glob) {
        routines.create(runtime.scopeFor(new ThreadId("routines")),
                name, "watch " + glob, "UTC", "look at what changed", new ThreadId(name));
    }

    @Test
    @DisplayName("the first scan baselines, a change fires once, and a quiet scan fires nothing")
    void firesOnChangeOnly() throws IOException, InterruptedException {
        addWatch("java-watch", "src/**/*.java");
        addWatch("md-watch", "*.md");

        assertEquals(List.of(), scanFor("java-watch", "md-watch"),
                "a watch over an existing tree must not fire on nothing having happened");
        assertEquals(List.of(), scanFor("java-watch", "md-watch"),
                "and a second quiet scan still fires nothing");

        // Filesystem timestamps are coarse; set the time explicitly rather than racing it.
        Files.writeString(workspace.resolve("src/Two.java"), "class Two {}");
        Files.setLastModifiedTime(workspace.resolve("src/Two.java"),
                java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(5)));

        List<String> fired = scanFor("java-watch", "md-watch");
        assertEquals(List.of("java-watch"), fired,
                "only the watch whose glob matches: " + fired);
        assertEquals(List.of(), scanFor("java-watch", "md-watch"),
                "the change was consumed; the next scan is quiet again");

        assertTrue(threads.history(new ThreadId("java-watch"), 10).stream()
                        .anyMatch(m -> m.message().displayText().contains("src/**/*.java")),
                "the routine is told what changed");
    }

    @Test
    @DisplayName("a deleted file is a change too")
    void deletionFires() throws IOException {
        addWatch("delete-watch", "src/**/*.java");
        scanFor("delete-watch");      // baseline

        Files.writeString(workspace.resolve("src/Three.java"), "class Three {}");
        scanFor("delete-watch");      // consume the addition
        Files.delete(workspace.resolve("src/Three.java"));

        assertEquals(List.of("delete-watch"), scanFor("delete-watch"),
                "a newest-mtime check would miss this, which is why the fingerprint covers paths");
    }

    @Test
    @DisplayName("a disabled watch is not scanned")
    void disabledWatchesAreSkipped() throws IOException {
        addWatch("paused-watch", "**/*.txt");
        scanFor("paused-watch");
        routines.list(runtime.scopeFor(new ThreadId("routines"))).stream()
                .filter(r -> r.name().equals("paused-watch"))
                .forEach(r -> routines.setEnabled(r.id(), false));

        Files.writeString(workspace.resolve("new.txt"), "hello");
        assertEquals(List.of(), scanFor("paused-watch"));
    }
}
