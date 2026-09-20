// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.bootstrap.config.StorageBackend;
import io.jclaw.ports.capability.CapabilityId;
import io.jclaw.ports.secret.SecretVault;
import io.jclaw.ports.secret.SecretVault.Binding;
import io.jclaw.ports.secret.SecretVault.SecretName;
import io.jclaw.ports.secret.SecretVaults;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(vaults.forTenant("../../etc/passwd").lease(new SecretName("x")).isPresent(),
                "a hostile name still gets a working vault of its own");

        // Assert on what was created, not on what happens to exist elsewhere. An earlier version
        // checked that `dir.resolve("../../etc/passwd")` did not exist, which passed on a deep
        // macOS temp path and failed on Linux CI — where two levels up from /tmp/junitXXXX really
        // is /etc/passwd, and it exists for reasons that have nothing to do with this code.
        List<Path> created;
        try (var files = Files.list(dir)) {
            created = files.toList();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertTrue(created.stream().anyMatch(path -> path.getFileName().toString().startsWith("secrets.")),
                "the hostile tenant's store is a direct child of the state directory: " + created);
        for (Path path : created) {
            String name = path.getFileName().toString();
            assertFalse(name.contains("/") || name.contains(".."),
                    "no separator or parent reference survives into a file name: " + name);
            assertEquals(dir, path.getParent(), "and nothing was written outside the state directory");
        }
    }

    @Test
    @DisplayName("two tenants never share a file, however alike their names look after sanitising")
    void fileNamesDoNotCollide() {
        // Sanitising alone would map both of these to the same name, and two tenants sharing a
        // vault is precisely the leak this class exists to prevent. A hash of the original name
        // is what makes them distinct; the readable prefix is only for an operator's benefit.
        assertNotEquals(TenantVaults.fileNameFor("a.b"), TenantVaults.fileNameFor("a_b"));
        assertNotEquals(TenantVaults.fileNameFor("../evil"), TenantVaults.fileNameFor(".._evil"));
        assertEquals(TenantVaults.fileNameFor("acme"), TenantVaults.fileNameFor("acme"),
                "and the same tenant always gets the same file");
        assertTrue(TenantVaults.fileNameFor("acme").startsWith("acme-"),
                "the readable half survives: " + TenantVaults.fileNameFor("acme"));

        TenantVaults vaults = vaults();
        vaults.forTenant("a.b").put(new SecretName("k"), "dotted-value-0", FETCH);
        vaults.forTenant("a_b").put(new SecretName("k"), "scored-value-1", FETCH);
        assertEquals("dotted-value-0", vaults.forTenant("a.b").lease(new SecretName("k")).orElseThrow().value());
        assertEquals("scored-value-1", vaults.forTenant("a_b").lease(new SecretName("k")).orElseThrow().value());
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
