// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.ports.extension.ExtensionRegistry;
import io.jclaw.adapter.out.persistence.extension.FilesystemExtensionRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jclaw extensions}: keygen, sign, install, list.
 *
 * <p>The subcommand under test is the one that decides trust, and the rule it enforces is the
 * point of the whole mechanism: a manifest's claims are not believed. An unsigned package installs
 * as COMMUNITY however it describes itself, and only a signature from a publisher the *operator*
 * has trusted makes it VERIFIED. A test that only installed a package would miss that entirely,
 * so this one signs with a key it generated and checks both answers.
 */
@SpringBootTest
class ExtensionsCommandTest {

    private static Path workspace;

    @TempDir
    Path scratch;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-extensions-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
    }

    @Autowired
    private ExtensionRegistry registry;

    @Autowired
    private FilesystemExtensionRegistry filesystemRegistry;

    private record Run(int exitCode, String out, String err) { }

    private Run run(Object command, String... args) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(command).execute(args);
            return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    /** A minimal skill package: the manifest, and the SKILL.md its kind requires. */
    private Path skillPackage(String name) throws IOException {
        Path dir = Files.createDirectories(scratch.resolve(name));
        Files.writeString(dir.resolve("jclaw-extension.json"),
                "{\"name\":\"" + name + "\",\"version\":\"1.0\",\"kind\":\"skill\","
                        + "\"description\":\"a probe\",\"publisher\":\"tester\"}",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: Probe\ndescription: d\nwhen-to-use: w\n---\nBody.\n", StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    @DisplayName("keygen writes both halves, and tells the operator how to trust the public one")
    void keygenWritesAPair() {
        Path keys = scratch.resolve("keys");

        Run result = run(new ExtensionsCommand.Keygen(), "--out", keys.toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(Files.isRegularFile(keys.resolve("publisher.key")), "the private half");
        assertTrue(Files.isRegularFile(keys.resolve("publisher.pub")), "the public half");
        assertTrue(result.out().contains("jclaw.trusted-publishers."),
                "a key nobody knows how to trust is not useful: " + result.out());
    }

    @Test
    @DisplayName("an unsigned package installs as COMMUNITY, whatever its manifest claims")
    void unsignedInstallsAsCommunity() throws IOException {
        Path pkg = skillPackage("unsigned-probe");

        Run result = run(new ExtensionsCommand.Install(registry), pkg.toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("COMMUNITY trust; every tool call will gate"),
                "the consequence is what the operator needs to read: " + result.out());
        assertEquals(io.jclaw.ports.capability.TrustClass.COMMUNITY,
                registry.find("unsigned-probe").orElseThrow().trust());
    }

    @Test
    @DisplayName("signing writes a signature over the package's digest")
    void signWritesASignature() throws IOException {
        Path keys = scratch.resolve("sign-keys");
        run(new ExtensionsCommand.Keygen(), "--out", keys.toString());
        Path pkg = skillPackage("signed-probe");

        Run result = run(new ExtensionsCommand.Sign(filesystemRegistry),
                pkg.toString(), "--key", keys.resolve("publisher.key").toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("signed "), result.out());
        assertTrue(Files.isRegularFile(pkg.resolve(FilesystemExtensionRegistry.SIGNATURE_FILE)),
                "the signature travels with the package");
    }

    @Test
    @DisplayName("signing something that is not a package fails rather than writing a signature")
    void signRefusesANonPackage() throws IOException {
        Path keys = scratch.resolve("refuse-keys");
        run(new ExtensionsCommand.Keygen(), "--out", keys.toString());
        Path empty = Files.createDirectories(scratch.resolve("not-a-package"));

        Run result = run(new ExtensionsCommand.Sign(filesystemRegistry),
                empty.toString(), "--key", keys.resolve("publisher.key").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("cannot sign"), result.err());
        assertFalse(Files.exists(empty.resolve(FilesystemExtensionRegistry.SIGNATURE_FILE)));
    }

    @Test
    @DisplayName("a package that is missing what its kind requires is refused")
    void incompletePackageRefused() throws IOException {
        Path dir = Files.createDirectories(scratch.resolve("no-skill-md"));
        Files.writeString(dir.resolve("jclaw-extension.json"),
                "{\"name\":\"no-skill-md\",\"version\":\"1.0\",\"kind\":\"skill\","
                        + "\"description\":\"d\",\"publisher\":\"tester\"}", StandardCharsets.UTF_8);

        Run result = run(new ExtensionsCommand.Install(registry), dir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("skill_package_needs_SKILL.md"), result.err());
    }

    @Test
    @DisplayName("--secret takes NAME=vault-entry, and anything else is refused before installing")
    void malformedSecretIsRefused() throws IOException {
        Path pkg = skillPackage("secret-probe");

        Run result = run(new ExtensionsCommand.Install(registry),
                pkg.toString(), "--secret", "NOT_A_PAIR");

        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("--secret expects NAME=secret-name"), result.err());
    }

    @Test
    @DisplayName("list says so plainly when nothing is installed, and names what is")
    void listReportsWhatIsThere() throws IOException {
        Run empty = run(new ExtensionsCommand.ListAll(new EmptyRegistry()));
        assertEquals(0, empty.exitCode());
        assertTrue(empty.out().contains("(no extensions installed)"), empty.out());

        run(new ExtensionsCommand.Install(registry), skillPackage("listed-probe").toString());
        Run listed = run(new ExtensionsCommand.ListAll(registry));
        assertTrue(listed.out().contains("listed-probe"), listed.out());
    }

    /** A registry with nothing in it, for the one case the real one cannot be put into. */
    private static final class EmptyRegistry implements ExtensionRegistry {
        @Override
        public io.jclaw.ports.Result<Installed, String> install(
                Path packageDir, java.util.Map<String, String> secrets) {
            return io.jclaw.ports.Result.err("not supported");
        }

        @Override public java.util.List<Installed> list() { return java.util.List.of(); }
        @Override public java.util.Optional<Installed> find(String name) { return java.util.Optional.empty(); }
        @Override public boolean remove(String name) { return false; }
        @Override public boolean setEnabled(String name, boolean enabled) { return false; }
    }
}
