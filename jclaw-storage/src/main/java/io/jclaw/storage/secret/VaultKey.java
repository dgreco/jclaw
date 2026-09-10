// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.secret;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Where the vault's key comes from.
 *
 * <p>Two sources, in order: a key the operator supplies (the application reads
 * {@code JCLAW_VAULT_KEY}, 32 bytes as base64 or 64 hex digits), or a key file beside the vault
 * that is generated on first use with owner-only permissions. The file is the convenient default
 * on one machine; the environment variable is for a deployment whose state directory is not
 * itself a secret store.
 */
public final class VaultKey {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private VaultKey() {
    }

    /** Decodes an operator-supplied key, or empty when the text is blank. */
    public static Optional<byte[]> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        byte[] key;
        if (trimmed.matches("[0-9a-fA-F]{64}")) {
            key = new byte[32];
            for (int i = 0; i < 32; i++) {
                key[i] = (byte) Integer.parseInt(trimmed.substring(2 * i, 2 * i + 2), 16);
            }
        } else {
            try {
                key = Base64.getDecoder().decode(trimmed);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("the vault key must be 32 bytes as base64 or 64 hex digits");
            }
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("the vault key must be 32 bytes, got " + key.length);
        }
        return Optional.of(key);
    }

    /** Reads the key file, generating it with owner-only permissions when absent. */
    public static byte[] loadOrCreate(Path keyFile) {
        Objects.requireNonNull(keyFile, "keyFile");
        try {
            if (Files.exists(keyFile)) {
                return parse(Files.readString(keyFile))
                        .orElseThrow(() -> new IllegalStateException("vault key file " + keyFile + " is empty"));
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key) + System.lineSeparator());
            try {
                Files.setPosixFilePermissions(keyFile, OWNER_ONLY);
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX filesystem; the file is still only as readable as the directory.
            }
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
