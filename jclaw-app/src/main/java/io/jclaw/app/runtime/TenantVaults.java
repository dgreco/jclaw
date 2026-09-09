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
        // TurnScope already constrains a tenant to a safe token, so this cannot climb out of the
        // state directory; the sanitising below is belt to that braces, since a path is being
        // built from a name that arrives with a run.
        String safe = tenant.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path path = defaultPath.resolveSibling("secrets." + safe + ".jsonl");
        return new FileSecretVault(backend.open("secrets_" + safe, path), key, clock);
    }
}
