// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.adapter.out.model.mock.MockModelProvider;
import io.jclaw.adapter.out.model.mock.MockModelProvider.Script;
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
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jclaw status}: the tail of the audit log, and one run folded out of it.
 *
 * <p>The projection is the same read model the HTTP surface serves, so what it says about a run
 * is what a UI would say. Worth pinning: it is folded from events rather than stored, an unknown
 * run is a non-zero exit rather than an empty-looking success, and {@code --spans} reads the log
 * directly — the summary may come from a cache, the spans never do.
 *
 * <p>Driven through picocli rather than by setting fields, so the options are parsed the way a
 * terminal would parse them.
 */
@SpringBootTest
class StatusCommandTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-status-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of());
        }
    }

    @Autowired
    private io.jclaw.ports.event.EventLog events;

    @Autowired
    private io.jclaw.adapter.out.persistence.projection.RunProjectionCache projections;

    @Autowired
    private JclawRuntime runtime;

    @Autowired
    private ModelProvider provider;

    /** One finished run with a capability call in it, so the projection has something to fold. */
    private String runOnce(String thread) {
        ((MockModelProvider) provider).reprogram(List.of(
                new Script.ToolCall("c1", "builtin.echo", Map.of("text", "hi")),
                new Script.Text("done")));
        return runtime.submit(new ThreadId(thread), "say hi", new AtomicBoolean(false))
                .run().value();
    }

    private record Run(int exitCode, String out) { }

    private Run status(String... args) {
        PrintStream real = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            // A fresh command each time. picocli sets only the options it parses, so a `--run`
            // left on a shared instance would make the next tail print a projection instead —
            // a failure that would come and go with test order.
            int code = new CommandLine(new StatusCommand(events, projections)).execute(args);
            return new Run(code, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(real);
        }
    }

    @Test
    @DisplayName("the tail shows recent events, newest activity included")
    void tailShowsEvents() {
        runOnce("status-tail");

        Run result = status();

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("run.finished"), result.out());
        assertTrue(result.out().contains("capability.invoked"), result.out());
    }

    @Test
    @DisplayName("--limit bounds how much is printed")
    void limitBounds() {
        runOnce("status-limit");

        long lines = status("-n", "3").out().lines().filter(line -> line.contains("run_")).count();
        assertTrue(lines <= 3, "asked for 3, got " + lines);
    }

    @Test
    @DisplayName("--run folds one run out of the log: status, usage, and what it called")
    void projectionOfOneRun() {
        String run = runOnce("status-projection");

        Run result = status("--run", run);

        assertEquals(0, result.exitCode(), result.out());
        assertTrue(result.out().contains("run          " + run), result.out());
        assertTrue(result.out().contains("thread       status-projection"), result.out());
        assertTrue(result.out().contains("status       COMPLETED"), result.out());
        assertTrue(result.out().contains("builtin.echo"),
                "the capability the run invoked belongs in its projection: " + result.out());
        assertTrue(result.out().contains("model calls  2"), result.out());
    }

    @Test
    @DisplayName("a run nobody ever submitted exits non-zero rather than printing an empty summary")
    void unknownRunFails() {
        Run result = status("--run", "run_neverexisted");

        assertEquals(1, result.exitCode(), "an empty-looking success would read as 'nothing wrong'");
        assertTrue(result.out().contains("(no events for run_neverexisted)"), result.out());
    }

    @Test
    @DisplayName("--spans reads the log itself, and names the run's own trace")
    void spansComeFromTheLog() {
        String run = runOnce("status-spans");

        Run result = status("--run", run, "--spans");

        assertEquals(0, result.exitCode(), result.out());
        assertTrue(result.out().contains("trace        "), result.out());
        assertTrue(result.out().contains("model.call"),
                "a finished run made model calls, so its spans must show them: " + result.out());
    }
}
