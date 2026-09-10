// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.domain.secret.SecretInjection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an MCP server's declared secret <em>names</em> into the environment its process is given.
 *
 * <p>A server that needs a token used to have the value written into its store row, which put a
 * credential in a file beside a vault built to prevent exactly that. The store now holds only the
 * name of a vault entry, and the value is leased here, at the moment the server starts, by the
 * application layer. Nothing below it ever holds the vault.
 *
 * <p>The binding is checked the same way a tool's is: the secret must name {@code mcp.connect} as
 * its capability. The host half is enforced only when the host is knowable. For a remote server
 * that is the endpoint, checked by the caller. For an extension it is the manifest's declared
 * hosts, which a {@code VERIFIED} package has signed. For a plain {@code mcp add} server there is
 * no claim to check against, and a subprocess's egress is not observable, so the capability alone
 * binds. That is a real limit, and it is why containerising a stdio server is worth doing.
 */
public final class McpCredentials {

    private McpCredentials() {
    }

    /**
     * Leases every named secret, or explains the first refusal.
     *
     * @param envSecrets    environment variable name to vault secret name
     * @param declaredHosts hosts the server claims to reach, empty when it makes no claim
     * @return the environment to hand the process, values included
     */
    public static Result<Map<String, String>, String> resolve(
            Map<String, String> envSecrets, Set<String> declaredHosts, SecretVault vault) {

        Objects.requireNonNull(envSecrets, "envSecrets");
        Objects.requireNonNull(declaredHosts, "declaredHosts");
        Objects.requireNonNull(vault, "vault");
        if (envSecrets.isEmpty()) {
            return Result.ok(Map.of());
        }

        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<String, String> wanted : envSecrets.entrySet()) {
            SecretVault.SecretName name;
            try {
                name = new SecretVault.SecretName(wanted.getValue());
            } catch (IllegalArgumentException e) {
                return Result.err("secret_name_invalid: " + wanted.getValue());
            }
            Optional<SecretVault.Lease> lease = vault.lease(name);
            if (lease.isEmpty()) {
                return Result.err("secret_unknown: " + wanted.getValue());
            }
            SecretVault.Binding binding = lease.get().info().binding();
            if (!binding.capability().equals(McpRegistry.CONNECT)) {
                return Result.err("secret_not_bound_to_capability: " + wanted.getValue());
            }
            if (!declaredHosts.isEmpty()) {
                Optional<String> refusal =
                        SecretInjection.refuse(binding, McpRegistry.CONNECT, declaredHosts);
                if (refusal.isPresent()) {
                    return Result.err(refusal.get() + ": " + wanted.getValue());
                }
            }
            environment.put(wanted.getKey(), lease.get().value());
        }
        return Result.ok(Map.copyOf(environment));
    }
}
