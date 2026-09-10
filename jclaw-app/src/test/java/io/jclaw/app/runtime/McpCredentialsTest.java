// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.storage.jsonl.JsonlFile;
import io.jclaw.storage.secret.FileSecretVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a server's process is allowed to be given, and what it is not. */
class McpCredentialsTest {

    @TempDir Path dir;

    private FileSecretVault vault() {
        return new FileSecretVault(new JsonlFile(dir.resolve("secrets.jsonl")), new byte[32], Clock.systemUTC());
    }

    @Test
    @DisplayName("a secret bound to mcp.connect is leased into the environment")
    void leases() {
        FileSecretVault vault = vault();
        vault.put(new SecretVault.SecretName("gh"), "ghp_value_0123456789",
                new SecretVault.Binding(McpRegistry.CONNECT, Set.of("api.github.com")));

        assertEquals(Map.of("GITHUB_TOKEN", "ghp_value_0123456789"),
                McpCredentials.resolve(Map.of("GITHUB_TOKEN", "gh"), Set.of("api.github.com"), vault).orElseThrow());
        assertEquals(Map.of(), McpCredentials.resolve(Map.of(), Set.of(), vault).orElseThrow(),
                "a server that wants nothing needs no vault at all");
    }

    @Test
    @DisplayName("a secret for another capability, another host, or no vault entry is refused")
    void refuses() {
        FileSecretVault vault = vault();
        vault.put(new SecretVault.SecretName("gh"), "ghp_value_0123456789",
                new SecretVault.Binding(McpRegistry.CONNECT, Set.of("api.github.com")));
        vault.put(new SecretVault.SecretName("elsewhere"), "other-value-0123456789",
                new SecretVault.Binding(CapabilityId.of("builtin.http_fetch"), Set.of("api.github.com")));

        assertTrue(McpCredentials.resolve(Map.of("T", "missing"), Set.of(), vault)
                .errorAsOptional().orElseThrow().startsWith("secret_unknown"));
        assertTrue(McpCredentials.resolve(Map.of("T", "elsewhere"), Set.of(), vault)
                .errorAsOptional().orElseThrow().startsWith("secret_not_bound_to_capability"),
                "a secret meant for a tool is not a secret meant for a server process");
        assertTrue(McpCredentials.resolve(Map.of("T", "gh"), Set.of("evil.example"), vault)
                .errorAsOptional().orElseThrow().startsWith("secret_host_not_allowed"),
                "a package that declares it reaches elsewhere does not get this secret");
        assertTrue(McpCredentials.resolve(Map.of("T", "not a name"), Set.of(), vault)
                .errorAsOptional().orElseThrow().startsWith("secret_name_invalid"));
    }

    @Test
    @DisplayName("without a declared host only the capability binds, which is the documented limit")
    void hostUnknowable() {
        FileSecretVault vault = vault();
        vault.put(new SecretVault.SecretName("gh"), "ghp_value_0123456789",
                new SecretVault.Binding(McpRegistry.CONNECT, Set.of("api.github.com")));

        // A plain `mcp add` server makes no host claim, and a subprocess's egress is not
        // observable, so the host half cannot be enforced. The capability still is.
        assertEquals("ghp_value_0123456789",
                McpCredentials.resolve(Map.of("T", "gh"), Set.of(), vault).orElseThrow().get("T"));
    }
}
