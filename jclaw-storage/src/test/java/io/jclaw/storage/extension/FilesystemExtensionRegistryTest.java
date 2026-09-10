// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.extension;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.TrustClass;
import io.jclaw.contracts.extension.ExtensionRegistry.Installed;
import io.jclaw.domain.extension.ExtensionSignature;
import io.jclaw.storage.jsonl.JsonlFile;
import io.jclaw.storage.skill.FilesystemSkillCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemExtensionRegistryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path dir;

    private FilesystemExtensionRegistry registry(Map<String, String> publishers) {
        return new FilesystemExtensionRegistry(dir.resolve("extensions"),
                new JsonlFile(dir.resolve("extensions.jsonl")), publishers, Optional.of(dir.resolve("skills")), CLOCK);
    }

    private Path skillPackage(String name) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("pkg-" + name));
        Files.writeString(pkg.resolve("jclaw-extension.json"),
                "{\"name\":\"" + name + "\",\"version\":\"1.0\",\"kind\":\"skill\",\"description\":\"a skill\"}");
        Files.writeString(pkg.resolve("SKILL.md"), "---\nname: Notes\ndescription: writes notes\nwhen-to-use: notes\n---\nDo it.");
        return pkg;
    }

    private Path mcpPackage(String name, String publisher) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("pkg-" + name));
        Files.writeString(pkg.resolve("jclaw-extension.json"),
                "{\"name\":\"" + name + "\",\"version\":\"2.1\",\"kind\":\"mcp\",\"command\":[\"srv\"],"
                        + "\"env\":[\"API_KEY\"],\"hosts\":[\"api.example.com\"],\"effect\":\"read_local\""
                        + (publisher == null ? "" : ",\"publisher\":\"" + publisher + "\"") + "}");
        return pkg;
    }

    @Test
    @DisplayName("an unsigned skill package installs as COMMUNITY and appears in the skill catalog")
    void skillPackage() throws IOException {
        FilesystemExtensionRegistry registry = registry(Map.of());
        Installed installed = registry.install(skillPackage("notes"), Map.of()).orElseThrow();

        assertEquals(TrustClass.COMMUNITY, installed.trust());
        assertEquals(64, installed.digest().length());
        FilesystemSkillCatalog catalog = new FilesystemSkillCatalog(dir.resolve("skills"));
        assertEquals(List.of("notes"), catalog.list().stream().map(s -> s.id()).toList());

        assertTrue(registry.setEnabled("notes", false));
        assertTrue(catalog.list().isEmpty(), "a disabled skill leaves the catalog");
        assertFalse(registry.find("notes").orElseThrow().enabled());
        assertTrue(registry.setEnabled("notes", true));
        assertEquals(1, catalog.list().size());

        assertTrue(registry.remove("notes"));
        assertTrue(catalog.list().isEmpty());
        assertFalse(Files.exists(dir.resolve("extensions/notes")));
        assertFalse(registry.remove("notes"));
    }

    @Test
    @DisplayName("a signed package by a trusted publisher is VERIFIED; a bad or untrusted signature is refused")
    void signedPackage() throws IOException {
        PublisherKeys.Pair acme = PublisherKeys.generate();
        PublisherKeys.Pair mallory = PublisherKeys.generate();
        FilesystemExtensionRegistry registry = registry(Map.of("acme", acme.publicKey()));

        Path pkg = mcpPackage("gh", "acme");
        String digest = registry.digestOf(pkg).orElseThrow();
        Files.writeString(pkg.resolve("jclaw-extension.sig"), ExtensionSignature.sign(digest, acme.privateKey()));

        assertEquals("secret_required_for: API_KEY", registry.install(pkg, Map.of()).errorAsOptional().orElseThrow());
        Installed installed = registry.install(pkg, Map.of("API_KEY", "gh-token")).orElseThrow();
        assertEquals(TrustClass.VERIFIED, installed.trust());
        assertEquals(EffectClass.READ_LOCAL, installed.effectiveEffect(), "a verified manifest's effect is believed");
        assertEquals(List.of("srv"), installed.manifest().command());
        assertEquals("gh-token", registry.find("gh").orElseThrow().secrets().get("API_KEY"),
                "the installation records which vault entry to lease, never the value");

        Files.writeString(pkg.resolve("jclaw-extension.json"),
                Files.readString(pkg.resolve("jclaw-extension.json")).replace("read_local", "destructive"));
        assertEquals("signature_invalid", registry.install(pkg, Map.of("API_KEY", "gh-token")).errorAsOptional().orElseThrow(),
                "editing a signed package breaks its signature");

        Path forged = mcpPackage("forged", "acme");
        Files.writeString(forged.resolve("jclaw-extension.sig"),
                ExtensionSignature.sign(registry.digestOf(forged).orElseThrow(), mallory.privateKey()));
        assertEquals("signature_invalid", registry.install(forged, Map.of("API_KEY", "gh-token")).errorAsOptional().orElseThrow());

        Path unknown = mcpPackage("unknown", "nobody");
        Files.writeString(unknown.resolve("jclaw-extension.sig"),
                ExtensionSignature.sign(registry.digestOf(unknown).orElseThrow(), mallory.privateKey()));
        assertTrue(registry.install(unknown, Map.of("API_KEY", "gh-token")).errorAsOptional().orElseThrow()
                .startsWith("publisher_not_trusted"));

        Path unsigned = mcpPackage("plain", null);
        Installed community = registry.install(unsigned, Map.of("API_KEY", "gh-token")).orElseThrow();
        assertEquals(TrustClass.COMMUNITY, community.trust());
        assertEquals(EffectClass.NETWORK, community.effectiveEffect(), "an unverified manifest's effect claim is ignored");
    }

    @Test
    @DisplayName("a package without a manifest, or with a broken one, is refused")
    void refusals() throws IOException {
        FilesystemExtensionRegistry registry = registry(Map.of());
        Path empty = Files.createDirectories(dir.resolve("empty"));
        assertEquals("manifest_missing", registry.install(empty, Map.of()).errorAsOptional().orElseThrow());
        Files.writeString(empty.resolve("jclaw-extension.json"), "{\"name\":\"x\",\"version\":\"1\",\"kind\":\"skill\"}");
        assertEquals("skill_package_needs_SKILL.md", registry.install(empty, Map.of()).errorAsOptional().orElseThrow());
        Result<Installed, String> bad = registry.install(dir.resolve("nope"), Map.of());
        assertEquals("package_not_a_directory", bad.errorAsOptional().orElseThrow());
    }
}
