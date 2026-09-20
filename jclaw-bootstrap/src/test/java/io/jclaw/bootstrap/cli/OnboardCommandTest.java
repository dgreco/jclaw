// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jclaw onboard}, which writes the config file a first run reads.
 *
 * <p>Three things it must get right. The credential is never written — it belongs in the
 * environment, and a config file that held one would be copied, committed and shared. Choosing
 * the {@code local} provider must collect a base URL, because that provider refuses to start
 * without one and a config that fails on the very next command is not onboarding. And an existing
 * file is not overwritten by accident.
 *
 * <p>{@code user.home} is redirected at a temp directory for the duration, so no case here can
 * touch the config of whoever is running the tests — including the ones that deliberately write.
 */
@SpringBootTest
class OnboardCommandTest {

    @TempDir
    Path home;

    @Autowired
    private io.jclaw.bootstrap.config.JclawProperties properties;

    private String realHome;
    private InputStream realIn;
    private PrintStream realOut;
    private PrintStream realErr;

    @BeforeEach
    void redirectHome() {
        realHome = System.getProperty("user.home");
        realIn = System.in;
        realOut = System.out;
        realErr = System.err;
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", realHome);
        System.setIn(realIn);
        System.setOut(realOut);
        System.setErr(realErr);
    }

    private record Run(int exitCode, String out, String err) { }

    /** Runs onboard with the given answers typed at its prompts, in order. */
    private Run onboard(String answers, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setIn(new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8)));
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        // A fresh command per run: picocli sets only the options it parses, so reusing one
        // instance would let --print from an earlier case linger into a later one — which is
        // exactly how the first draft of this test passed while asserting nothing.
        int code = new CommandLine(new OnboardCommand(properties)).execute(args);
        System.setOut(realOut);
        System.setErr(realErr);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path configFile() {
        return home.resolve(".jclaw").resolve("jclaw.yaml");
    }

    @Test
    @DisplayName("--print shows what would be written and writes nothing")
    void printWritesNothing() {
        Run result = onboard("anthropic\nclaude-opus-5\ntrusted\n", "--print");

        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("provider: anthropic"), result.out());
        assertTrue(result.out().contains("approval-mode: trusted"), result.out());
        assertTrue(result.out().contains("Not written (--print)."), result.out());
        assertFalse(Files.exists(configFile()), "--print must leave the filesystem alone");
    }

    @Test
    @DisplayName("the file it writes carries no credential, only where to put one")
    void neverWritesACredential() throws Exception {
        Run result = onboard("openai\ngpt-5.2\ninteractive\n");

        assertEquals(0, result.exitCode(), result.out() + result.err());
        String written = Files.readString(configFile(), StandardCharsets.UTF_8);
        assertTrue(written.contains("provider: openai"), written);
        assertTrue(written.contains("Credentials are intentionally not stored here"), written);
        assertFalse(written.toLowerCase(java.util.Locale.ROOT).contains("api-key:")
                        || written.contains("OPENAI_API_KEY:"),
                "a config file holding a key gets copied, committed and shared: " + written);
        assertTrue(result.out().contains("OPENAI_API_KEY"),
                "the hint says where the key goes instead: " + result.out());
    }

    @Test
    @DisplayName("choosing local collects the base URL, without which that provider cannot start")
    void localCollectsItsBaseUrl() throws Exception {
        Run result = onboard("local\nqwen2.5-coder-7b-instruct\ninteractive\nhttp://localhost:1234/v1\n");

        assertEquals(0, result.exitCode(), result.out() + result.err());
        String written = Files.readString(configFile(), StandardCharsets.UTF_8);
        assertTrue(written.contains("local-base-url: http://localhost:1234/v1"), written);
    }

    @Test
    @DisplayName("an answer that is not on the menu is rejected and asked again")
    void invalidChoiceIsReAsked() {
        Run result = onboard("wishful\nanthropic\nclaude-opus-5\ninteractive\n", "--print");

        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("not one of"), result.out());
        assertTrue(result.out().contains("provider: anthropic"), result.out());
    }

    @Test
    @DisplayName("exhausted input takes the defaults rather than looping forever")
    void eofTakesDefaults() {
        Run result = onboard("", "--print");

        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("provider: "), result.out());
        assertTrue(result.out().contains("Not written (--print)."), result.out());
    }

    @Test
    @DisplayName("an existing config is refused, and --force is what overwrites it")
    void existingFileIsProtected() throws Exception {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "jclaw:\n  provider: mock\n", StandardCharsets.UTF_8);

        Run refused = onboard("anthropic\nclaude-opus-5\ninteractive\n");
        assertEquals(1, refused.exitCode());
        assertTrue(refused.err().contains("already exists"), refused.err());
        assertTrue(Files.readString(configFile(), StandardCharsets.UTF_8).contains("provider: mock"),
                "the refusal must leave the old file intact");

        Run forced = onboard("anthropic\nclaude-opus-5\ninteractive\n", "--force");
        assertEquals(0, forced.exitCode(), forced.out() + forced.err());
        assertTrue(Files.readString(configFile(), StandardCharsets.UTF_8).contains("provider: anthropic"));
    }
}
