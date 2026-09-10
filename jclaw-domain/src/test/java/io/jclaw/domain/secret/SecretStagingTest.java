// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.secret;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault.Binding;
import io.jclaw.contracts.secret.SecretVault.SecretName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two handoffs must not overlap: a secret scoped to a host cannot be staged into a process
 * that could send it anywhere, and a secret staged for a subprocess cannot be put in a URL.
 */
class SecretStagingTest {

    private static final CapabilityId SHELL = CapabilityId.of("builtin.shell");
    private static final CapabilityId FETCH = CapabilityId.of("builtin.http_fetch");

    @Test
    @DisplayName("staging needs the capability and the reserved subprocess binding")
    void bindingRules() {
        Binding subprocess = new Binding(SHELL, Set.of(SecretStaging.SUBPROCESS));
        assertEquals(Optional.empty(), SecretStaging.refuse(subprocess, SHELL));

        assertEquals(Optional.of("secret_not_bound_to_capability"),
                SecretStaging.refuse(subprocess, FETCH));
        assertEquals(Optional.of("secret_not_bound_for_subprocess"),
                SecretStaging.refuse(new Binding(SHELL, Set.of("api.github.com")), SHELL),
                "a host binding scoped where the value may be sent; a subprocess can send it anywhere");
        assertEquals(Optional.of("secret_not_bound_for_subprocess"),
                SecretStaging.refuse(new Binding(SHELL, Set.of(SecretStaging.SUBPROCESS, "api.github.com")), SHELL),
                "the reserved host is exclusive, not one option among several");
    }

    @Test
    @DisplayName("the reserved host matches no real host, so the two handoffs cannot cross")
    void reservedHostIsInert() {
        Binding subprocess = new Binding(SHELL, Set.of(SecretStaging.SUBPROCESS));
        assertTrue(SecretInjection.refuse(subprocess, SHELL, Set.of("api.github.com")).isPresent(),
                "a staged secret can never be injected into a URL");
        assertTrue(SecretInjection.refuse(subprocess, SHELL, Set.of("*")).isPresent(),
                "not even by writing the reserved token as a host in the arguments");
    }

    @Test
    @DisplayName("a staging request is names to names, and every part is validated")
    void requestParsing() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("GH_TOKEN", "gh-cli");
        request.put("_UNDERSCORED", "other");
        Map<String, SecretName> parsed = SecretStaging.requested(request).orElseThrow();
        assertEquals(Set.of("GH_TOKEN", "_UNDERSCORED"), parsed.keySet());
        assertEquals(new SecretName("gh-cli"), parsed.get("GH_TOKEN"));

        assertTrue(SecretStaging.requested(null).orElseThrow().isEmpty(),
                "asking for nothing is not an error");
        assertTrue(SecretStaging.requested(Map.of()).orElseThrow().isEmpty());
        assertEquals("secret_env_must_be_an_object",
                SecretStaging.requested("GH_TOKEN=gh").errorAsOptional().orElseThrow());
        assertEquals("secret_env_variable_invalid",
                SecretStaging.requested(Map.of("2BAD", "gh")).errorAsOptional().orElseThrow());
        assertEquals("secret_env_variable_invalid",
                SecretStaging.requested(Map.of("PATH=x", "gh")).errorAsOptional().orElseThrow(),
                "a variable name that smuggles an assignment is not a variable name");
        assertEquals("secret_env_name_invalid",
                SecretStaging.requested(Map.of("GH", "not a secret name")).errorAsOptional().orElseThrow());
    }
}
