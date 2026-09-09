package io.jclaw.storage.secret;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.secret.SecretVault.Binding;
import io.jclaw.contracts.secret.SecretVault.SecretName;
import io.jclaw.storage.jsonl.JsonlFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSecretVaultTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Binding BINDING = new Binding(CapabilityId.of("builtin.http_fetch"), Set.of("api.example.com"));

    @TempDir Path dir;

    @Test
    @DisplayName("a secret round-trips, and its plaintext never touches the disk")
    void roundTripsEncrypted() throws Exception {
        byte[] key = VaultKey.loadOrCreate(dir.resolve("vault.key"));
        FileSecretVault vault = new FileSecretVault(new JsonlFile(dir.resolve("secrets.jsonl")), key, CLOCK);

        vault.put(new SecretName("api"), "s3cret-value-1234567890", BINDING);

        SecretVault.Lease lease = vault.lease(new SecretName("api")).orElseThrow();
        assertEquals("s3cret-value-1234567890", lease.value());
        assertEquals(BINDING, lease.info().binding());
        assertFalse(lease.toString().contains("s3cret"), "a lease does not print its value");

        String onDisk = Files.readString(dir.resolve("secrets.jsonl"));
        assertFalse(onDisk.contains("s3cret"), "the file holds ciphertext only");
        assertTrue(onDisk.contains("api.example.com"), "bindings are readable: they are policy, not secret");

        assertEquals(1, vault.list().size());
        assertTrue(vault.remove(new SecretName("api")));
        assertTrue(vault.lease(new SecretName("api")).isEmpty());
        assertFalse(vault.remove(new SecretName("api")));
    }

    @Test
    @DisplayName("another key cannot open the vault, and the key file is owner-only")
    void wrongKeyFails() throws Exception {
        Path keyFile = dir.resolve("vault.key");
        byte[] key = VaultKey.loadOrCreate(keyFile);
        assertEquals(key.length, 32);
        assertEquals(java.util.Arrays.toString(key), java.util.Arrays.toString(VaultKey.loadOrCreate(keyFile)),
                "a second load reads the same key");
        try {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(keyFile)));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }

        JsonlFile file = new JsonlFile(dir.resolve("secrets.jsonl"));
        new FileSecretVault(file, key, CLOCK).put(new SecretName("api"), "value-0123456789", BINDING);

        byte[] other = new byte[32];
        other[0] = 1;
        FileSecretVault wrong = new FileSecretVault(file, other, CLOCK);
        assertEquals(1, wrong.list().size(), "metadata is readable without the key");
        assertThrows(IllegalStateException.class, () -> wrong.lease(new SecretName("api")));
    }

    @Test
    @DisplayName("an operator key is accepted as hex or base64, and must be 32 bytes")
    void parsesKeys() {
        assertEquals(32, VaultKey.parse("00".repeat(32)).orElseThrow().length);
        assertEquals(32, VaultKey.parse(java.util.Base64.getEncoder().encodeToString(new byte[32])).orElseThrow().length);
        assertTrue(VaultKey.parse("  ").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> VaultKey.parse("dG9vc2hvcnQ="));
    }
}
