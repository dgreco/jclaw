// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.app.config.StorageBackend;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.secret.SecretVaults;
import io.jclaw.storage.secret.FileSecretVault;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A vault per tenant, opened on first use.
 *
 * <p>The default tenant keeps the original store, so a single-user installation is untouched:
 * {@code secrets.jsonl} is still where its credentials are, and nothing has to be migrated. Every
 * other tenant gets its own — {@code secrets.<tenant>.jsonl}, or its own rows under the SQL
 * backend — so a secret named {@code api-key} means one tenant's key and cannot be leased by
 * another. Two tenants naming a secret identically is the ordinary case, not a collision.
 *
 * <p>They share one encryption key. Separating tenants is about which credential a run can reach,
 * and the key answers a different question — whether an operator reading the state directory
 * learns anything. Per-tenant keys would need per-tenant key custody, which is a real feature and
 * not this one; pretending otherwise by generating more keys with the same custody would add
 * ceremony and no protection.
 *
 * <p>Vaults are cached because a run leases on the dispatch path and reopening a store per call
 * would be measurable. Nothing is evicted: the number of tenants is the number of configured
 * users, and a tenant that stops running costs one open store.
 */
public final class TenantVaults implements SecretVaults {

    /** The tenant whose secrets live in the plain, unsuffixed store. */
    public static final String DEFAULT_TENANT = "local";

    private final StorageBackend backend;
    private final Path defaultPath;
    private final byte[] key;
    private final Clock clock;
    private final Map<String, SecretVault> opened = new ConcurrentHashMap<>();

    public TenantVaults(StorageBackend backend, Path defaultPath, byte[] key, Clock clock) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.defaultPath = Objects.requireNonNull(defaultPath, "defaultPath");
        this.key = Objects.requireNonNull(key, "key").clone();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SecretVault forTenant(String tenant) {
        String name = tenant == null || tenant.isBlank() ? DEFAULT_TENANT : tenant;
        return opened.computeIfAbsent(name, this::open);
    }

    /** The default tenant's vault: what the {@code secrets} command and the CLI operate on. */
    public SecretVault primary() {
        return forTenant(DEFAULT_TENANT);
    }

    private SecretVault open(String tenant) {
        if (DEFAULT_TENANT.equals(tenant)) {
            return new FileSecretVault(backend.open("secrets", defaultPath), key, clock);
        }
        String safe = fileNameFor(tenant);
        Path path = defaultPath.resolveSibling("secrets." + safe + ".jsonl");
        return new FileSecretVault(backend.open("secrets_" + safe, path), key, clock);
    }

    /**
     * A file-name segment for a tenant: readable, inert, and unique.
     *
     * <p>{@code TurnScope} already forbids {@code /} in a tenant, so a path built from one cannot
     * climb out of the state directory. The sanitising here is belt to that braces — but
     * sanitising alone would be worse than nothing, because collapsing every awkward character to
     * {@code _} makes {@code a.b} and {@code a_b} the same file, and two tenants sharing a vault
     * is precisely the leak this class exists to prevent.
     *
     * <p>So the readable part is followed by a short hash of the <em>original</em> name. The hash
     * is what guarantees distinctness; the sanitised prefix is only so an operator listing the
     * state directory can tell whose vault is whose.
     */
    static String fileNameFor(String tenant) {
        String readable = tenant.replaceAll("[^A-Za-z0-9_-]", "_");
        if (readable.length() > 40) {
            readable = readable.substring(0, 40);
        }
        return readable + "-" + shortHash(tenant);
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
