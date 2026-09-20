// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.bootstrap.config.JclawProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jclaw doctor} on a healthy configuration.
 *
 * <p>Two things here are behaviour rather than presentation, and both are why this command
 * exists. Its exit code is a CI preflight, so a sound configuration must exit 0 and a broken one
 * must not. And it names the credential the <em>configured</em> provider needs — a user running
 * openrouter was once told to set ANTHROPIC_API_KEY, which is worse than saying nothing.
 */
@SpringBootTest
class DoctorCommandTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-doctor-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.provider", () -> "mock");
        registry.add("jclaw.approval-mode", () -> "interactive");
        registry.add("jclaw.allow-private-networks", () -> "false");
    }

    @Autowired
    private DoctorCommand doctor;

    @Autowired
    private JclawProperties configured;

    /** Runs doctor with stdout captured, returning its exit code and everything it printed. */
    private record Run(int exitCode, String out, String err) { }

    private Run run() {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = doctor.call();
            return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    @Test
    @DisplayName("a sound configuration exits 0, which is what makes it usable as a preflight")
    void soundConfigurationPasses() {
        Run result = run();

        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("All checks passed."), result.out());
        assertTrue(result.err().isBlank(), "nothing is reported as a failure: " + result.err());
    }

    @Test
    @DisplayName("it reports the posture, not just the plumbing")
    void reportsPosture() {
        String out = run().out();

        assertTrue(out.contains("Configuration"), out);
        assertTrue(out.contains("Security posture"), out);
        assertTrue(out.contains("Checks"), out);
        // The line that matters most on a default install: tools cannot reach the private network.
        assertTrue(out.contains("private networks     blocked"), out);
    }

    @Test
    @DisplayName("a provider needing no credential is skipped rather than reported as missing")
    void noCredentialNeededIsSkipped() {
        String out = run().out();

        assertTrue(out.contains("[skip] provider 'mock' needs no credentials"), out);
        assertFalse(out.contains("[fail]"), out);
    }

    @Test
    @DisplayName("no warning is printed when nothing about the posture is unusual")
    void quietWhenNothingIsRisky() {
        String out = run().out();
        assertFalse(out.contains("[warn]"), "a warning here would teach people to ignore them: " + out);
    }

    @Test
    @DisplayName("the configured provider decides whether a credential is even wanted")
    void credentialRequirementFollowsTheProvider() {
        assertEquals(Optional.empty(), configured.credentialEnvVar(),
                "the mock needs no key, so doctor must not invent one to complain about");
    }
}
