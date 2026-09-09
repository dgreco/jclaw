package io.jclaw.app.runtime;

import io.jclaw.app.config.StorageBackend;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.secret.SecretVault.Binding;
import io.jclaw.contracts.secret.SecretVault.SecretName;
import io.jclaw.contracts.secret.SecretVaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two tenants naming a secret identically is the ordinary case, not a collision.
 */
class TenantVaultsTest {

    private static final Binding FETCH =
            new Binding(CapabilityId.of("builtin.http_fetch"), Set.of("api.example.com"));

    @TempDir Path dir;

    private TenantVaults vaults() {
        return new TenantVaults(StorageBackend.jsonl(dir), dir.resolve("secrets.jsonl"),
                new byte[32], Clock.systemUTC());
    }

    @Test
    @DisplayName("each tenant's secrets are its own, under the same name")
    void tenantsAreSeparate() {
        TenantVaults vaults = vaults();
        vaults.forTenant("acme").put(new SecretName("api-key"), "acme-value-000", FETCH);
        vaults.forTenant("globex").put(new SecretName("api-key"), "globex-value-111", FETCH);

        assertEquals("acme-value-000",
                vaults.forTenant("acme").lease(new SecretName("api-key")).orElseThrow().value());
        assertEquals("globex-value-111",
                vaults.forTenant("globex").lease(new SecretName("api-key")).orElseThrow().value());
        assertTrue(vaults.forTenant("initech").lease(new SecretName("api-key")).isEmpty(),
                "a tenant that stored nothing sees nothing, not somebody else's");

        assertEquals(List.of("api-key"),
                vaults.forTenant("acme").list().stream().map(info -> info.name().value()).toList(),
                "listing shows one tenant's secrets, not every tenant's");
    }

    @Test
    @DisplayName("the default tenant keeps the original store, so nothing has to be migrated")
    void defaultTenantIsUnchanged() {
        TenantVaults vaults = vaults();
        vaults.primary().put(new SecretName("legacy"), "legacy-value-222", FETCH);

        assertTrue(Files.exists(dir.resolve("secrets.jsonl")),
                "a single-user installation's credentials stay where they were");
        assertSame(vaults.primary(), vaults.forTenant(TenantVaults.DEFAULT_TENANT));
        assertSame(vaults.forTenant("acme"), vaults.forTenant("acme"), "opened once, then cached");
        assertTrue(vaults.forTenant("acme").lease(new SecretName("legacy")).isEmpty());
    }

    @Test
    @DisplayName("a tenant name never escapes the state directory")
    void tenantNamesAreSanitised() {
        TenantVaults vaults = vaults();
        vaults.forTenant("../../etc/passwd").put(new SecretName("x"), "value-333", FETCH);

        assertTrue(vaults.forTenant("../../etc/passwd").lease(new SecretName("x")).isPresent());
        assertFalse(Files.exists(dir.resolve("../../etc/passwd")));
        try (var files = Files.list(dir)) {
            assertTrue(files.allMatch(path -> path.getParent().equals(dir)),
                    "every store the vaults opened is inside the state directory");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("a shared vaults port hands every tenant the same one, which is what the CLI wants")
    void sharedIsOneVault() {
        SecretVault one = SecretVault.empty();
        SecretVaults shared = SecretVaults.shared(one);
        assertSame(one, shared.forTenant("local"));
        assertSame(one, shared.forTenant("anybody"));
    }
}
