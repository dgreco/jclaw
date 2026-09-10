// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jclaw doctor} on a configuration that has given things away.
 *
 * <p>The point of the command is that a harness reaching private networks, or running shell
 * commands without asking, is in a different threat position from the defaults — and that the
 * person running it should see that without reading the config file. A warning that fails to
 * appear here is worse than no warning at all, because the report then reads as an all-clear.
 *
 * <p>Also pinned: an openrouter deployment is never told to set {@code ANTHROPIC_API_KEY}. That
 * was a real defect, and asserting on the absence of the wrong variable holds whether or not the
 * right one happens to be exported on the machine running the tests.
 */
@SpringBootTest
class DoctorPostureTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-doctor-posture-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        // Everything a careless deployment might do at once.
        registry.add("jclaw.provider", () -> "openrouter");
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.allow-private-networks", () -> "true");
        registry.add("jclaw.shell-backend", () -> "host");
    }

    @Autowired
    private DoctorCommand doctor;

    private String output() {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            doctor.call();
            return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    @Test
    @DisplayName("reachable private networks are reported as ALLOWED and warned about")
    void privateNetworksAreWarnedAbout() {
        String out = output();

        assertTrue(out.contains("private networks     ALLOWED"),
                "the posture line shouts, because it is the SSRF surface: " + out);
        assertTrue(out.contains("[warn]") && out.contains("SSRF"), out);
    }

    @Test
    @DisplayName("auto-approved shell execution is warned about, and the wording names the risk")
    void unattendedShellIsWarnedAbout() {
        String out = output();

        assertTrue(out.contains("prompt injection becomes"), out);
        assertTrue(out.contains("arbitrary code execution on this host"),
                "with the host backend the blast radius is the host, and it should say so: " + out);
        assertTrue(out.contains("jclaw.shell-backend=docker"),
                "a warning that names the mitigation is worth more than one that does not: " + out);
    }

    @Test
    @DisplayName("an openrouter deployment is never told to set Anthropic's key")
    void namesTheRightCredential() {
        String out = output();

        assertFalse(out.contains("ANTHROPIC_API_KEY"),
                "the variable named must follow the configured provider: " + out);
        assertTrue(out.contains("openrouter"), out);
    }
}
