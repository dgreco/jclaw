// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.secret;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault.Binding;
import io.jclaw.contracts.secret.SecretVault.SecretName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretInjectionTest {

    private static final CapabilityId FETCH = CapabilityId.of("builtin.http_fetch");
    private static final Binding GITHUB = new Binding(FETCH, Set.of("api.github.com", "*.githubusercontent.com"));

    @Test
    @DisplayName("references are found in nested maps and lists, once each")
    void findsReferences() {
        Map<String, Object> arguments = Map.of(
                "url", "https://api.github.com/user",
                "headers", Map.of("Authorization", "Bearer {{secret:gh}}", "X-Other", "{{secret:gh}}"),
                "list", List.of("{{secret:second}}", 3));

        assertEquals(Set.of(new SecretName("gh"), new SecretName("second")),
                SecretInjection.references(arguments));
        assertTrue(SecretInjection.references(Map.of("url", "https://x.y")).isEmpty());
    }

    @Test
    @DisplayName("injection substitutes every reference and leaves the original untouched")
    void injects() {
        Map<String, Object> arguments = Map.of(
                "url", "https://api.github.com/user",
                "headers", Map.of("Authorization", "Bearer {{secret:gh}}"));

        Map<String, Object> injected = SecretInjection.inject(
                arguments, Map.of(new SecretName("gh"), "ghp_realvalue")).orElseThrow();

        assertEquals("Bearer ghp_realvalue", ((Map<?, ?>) injected.get("headers")).get("Authorization"));
        assertEquals("Bearer {{secret:gh}}", ((Map<?, ?>) arguments.get("headers")).get("Authorization"));
        assertEquals(new SecretName("gh"), SecretInjection.inject(arguments, Map.of()).errorAsOptional().orElseThrow(),
                "a missing value names the reference that lacked one");
    }

    @Test
    @DisplayName("every URL-shaped argument contributes its host")
    void collectsHosts() {
        assertEquals(Set.of("api.github.com", "evil.example"), SecretInjection.urlHosts(Map.of(
                "url", "https://api.github.com/user",
                "callback", "http://evil.example/x",
                "text", "not a url")));
        assertEquals(Set.of(""), SecretInjection.urlHosts(Map.of("url", "http://")),
                "an unparseable URL yields a host nothing can be bound to");
        assertEquals("api.github.com", SecretInjection.hostOf("HTTPS://user:pw@API.github.com:443/x?y#z"));
        assertEquals("::1", SecretInjection.hostOf("http://[::1]:8080/"));
        assertEquals("evil.example", SecretInjection.hostOf("https://api.github.com@evil.example/"),
                "userinfo cannot disguise the real host");
    }

    @Test
    @DisplayName("a binding refuses another capability, an unbound host, or no host at all")
    void refuses() {
        assertEquals(Optional.empty(), SecretInjection.refuse(GITHUB, FETCH, Set.of("api.github.com")));
        assertEquals(Optional.empty(), SecretInjection.refuse(GITHUB, FETCH, Set.of("raw.githubusercontent.com")));
        assertEquals(Optional.of("secret_not_bound_to_capability"),
                SecretInjection.refuse(GITHUB, CapabilityId.of("builtin.shell"), Set.of("api.github.com")));
        assertEquals(Optional.of("secret_host_not_allowed"),
                SecretInjection.refuse(GITHUB, FETCH, Set.of("api.github.com", "evil.example")),
                "one bad host among good ones is enough to refuse: the value would still travel");
        assertEquals(Optional.of("secret_host_not_allowed"),
                SecretInjection.refuse(GITHUB, FETCH, Set.of("notgithub.com")));
        assertEquals(Optional.of("secret_requires_target_host"), SecretInjection.refuse(GITHUB, FETCH, Set.of()));
    }

    @Test
    @DisplayName("names and bindings are validated at construction")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new SecretName("has space"));
        assertThrows(IllegalArgumentException.class, () -> new SecretName(""));
        assertThrows(IllegalArgumentException.class, () -> new Binding(FETCH, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Binding(FETCH, Set.of("host:443")));
    }
}
