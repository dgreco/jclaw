// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.extension;

import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.extension.ExtensionRegistry.Kind;
import io.jclaw.contracts.extension.ExtensionRegistry.Manifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionPackagingTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the digest depends on paths and bytes, not on map order")
    void digest() {
        String a = PackageDigest.of(Map.of("SKILL.md", bytes("x"), "jclaw-extension.json", bytes("{}")));
        String b = PackageDigest.of(Map.of("jclaw-extension.json", bytes("{}"), "SKILL.md", bytes("x")));
        assertEquals(a, b);
        assertNotEquals(a, PackageDigest.of(Map.of("SKILL.md", bytes("y"), "jclaw-extension.json", bytes("{}"))));
        assertNotEquals(a, PackageDigest.of(Map.of("OTHER.md", bytes("x"), "jclaw-extension.json", bytes("{}"))));
        assertNotEquals(PackageDigest.of(Map.of("a", bytes("xy"), "b", bytes(""))),
                PackageDigest.of(Map.of("a", bytes("x"), "b", bytes("y"))), "bytes cannot move between files");
        assertEquals(64, a.length());
    }

    @Test
    @DisplayName("a signature verifies under the publisher's key and nothing else")
    void signature() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String otherPub = Base64.getEncoder().encodeToString(other.getPublic().getEncoded());
        String digest = PackageDigest.of(Map.of("f", bytes("content")));

        String sig = ExtensionSignature.sign(digest, priv);
        assertTrue(ExtensionSignature.verify(digest, sig, pub));
        assertFalse(ExtensionSignature.verify(digest, sig, otherPub), "another publisher's key");
        assertFalse(ExtensionSignature.verify(digest.replace('a', 'b'), sig, pub), "a changed package");
        assertFalse(ExtensionSignature.verify(digest, "not base64!", pub), "garbage is a failure, not an exception");
        assertFalse(ExtensionSignature.verify(digest, sig, "garbage"));
    }

    @Test
    @DisplayName("manifests are validated field by field")
    void manifests() {
        Manifest ok = ManifestParser.parse(Map.of(
                "name", "github-tools", "version", "1.0.0", "kind", "mcp", "description", "d",
                "command", List.of("npx", "-y", "srv"), "env", List.of("GITHUB_TOKEN"),
                "hosts", List.of("api.github.com"), "effect", "read_local", "publisher", "acme")).orElseThrow();
        assertEquals(Kind.MCP, ok.kind());
        assertEquals(EffectClass.READ_LOCAL, ok.effect());
        assertEquals(Optional.of("acme"), ok.publisher());

        assertEquals(EffectClass.NETWORK, ManifestParser.parse(Map.of(
                "name", "s", "version", "1", "kind", "skill")).orElseThrow().effect(), "effect defaults to NETWORK");
        assertEquals("manifest_requires_name_version_kind",
                ManifestParser.parse(Map.of("name", "x")).errorAsOptional().orElseThrow());
        assertEquals("manifest_kind_must_be_skill_mcp_or_wasm",
                ManifestParser.parse(Map.of("name", "x", "version", "1", "kind", "applet")).errorAsOptional().orElseThrow());
        assertTrue(ManifestParser.parse(Map.of("name", "x", "version", "1", "kind", "wasm"))
                .errorAsOptional().orElseThrow().startsWith("manifest_invalid"), "a wasm module must offer a tool");
        Manifest wasm = ManifestParser.parse(Map.of(
                "name", "counter", "version", "1", "kind", "wasm",
                "permissions", List.of("log"), "tools", List.of("count"))).orElseThrow();
        assertEquals(Kind.WASM, wasm.kind());
        assertEquals(List.of("log"), wasm.permissions());
        assertEquals(List.of("count"), wasm.tools());
        assertTrue(ManifestParser.parse(Map.of("name", "x", "version", "1", "kind", "mcp"))
                .errorAsOptional().orElseThrow().startsWith("manifest_invalid"), "mcp needs a command");
        assertTrue(ManifestParser.parse(Map.of("name", "Bad Name", "version", "1", "kind", "skill"))
                .errorAsOptional().orElseThrow().startsWith("manifest_invalid"));
    }
}
