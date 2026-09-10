// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.config;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.kernel.capability.CapabilityPolicy;
import io.jclaw.kernel.guard.EgressGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding of the denied set and egress lists from configuration. The code paths existed; what
 * this pins is that configuration actually reaches them, including the Spring quirk that binds
 * an absent list to one blank element.
 */
class PolicyWiringTest {

    private final JclawConfiguration configuration = new JclawConfiguration();

    /**
     * Builds properties the way the application does, through Spring's {@link Binder}.
     *
     * <p>This used to call the canonical constructor with every component in order, which meant
     * every unrelated setting added to {@code JclawProperties} broke two tests that do not care
     * about it. Binding from a property map is both less brittle and closer to the truth: it is
     * the code path that runs at startup, so a property that binds here binds in the application.
     */
    private static JclawProperties properties(
            List<String> denied, List<String> allow, List<String> deny) {
        return properties(denied, allow, deny, java.util.Map.of(), java.util.Map.of());
    }

    private static JclawProperties properties(
            List<String> denied, List<String> allow, List<String> deny,
            java.util.Map<String, String> toolEgress, java.util.Map<String, String> toolRateLimits) {
        java.util.Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("jclaw.workspace", ".");
        source.put("jclaw.state-dir", Path.of("build", "test-state").toString());
        index(source, "jclaw.denied-capabilities", denied);
        index(source, "jclaw.egress-allowlist", allow);
        index(source, "jclaw.egress-denylist", deny);
        toolEgress.forEach((key, value) -> source.put("jclaw.tool-egress[" + key + "]", value));
        toolRateLimits.forEach((key, value) -> source.put("jclaw.tool-rate-limits[" + key + "]", value));
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("jclaw", JclawProperties.class)
                .orElseThrow(() -> new IllegalStateException("jclaw.* did not bind"));
    }

    /**
     * Writes a list as indexed properties, so a blank element survives.
     *
     * <p>A comma-joined value would be split by the binder and the empty string dropped, which
     * would hide the very quirk these tests exist to pin: Spring binds an absent list property to
     * a single blank element, and the wiring has to treat that as an empty list.
     */
    private static void index(java.util.Map<String, Object> source, String key, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            source.put(key + "[" + i + "]", values.get(i));
        }
    }

    @Test
    @DisplayName("denied capabilities reach the policy, and a blank default denies nothing")
    void deniedCapabilities() {
        CapabilityPolicy policy = configuration.capabilityPolicy(
                properties(List.of("builtin.shell", " builtin.http_fetch "), List.of(""), List.of("")));

        assertTrue(policy.isDenied(CapabilityId.builtin("shell")));
        assertTrue(policy.isDenied(CapabilityId.builtin("http_fetch")), "entries are trimmed");
        assertFalse(policy.isDenied(CapabilityId.builtin("read_file")));

        CapabilityPolicy unset = configuration.capabilityPolicy(properties(List.of(""), List.of(""), List.of("")));
        assertTrue(unset.denied().isEmpty(), "Spring's blank-element default must deny nothing");
    }

    @Test
    @DisplayName("a malformed denied entry fails startup with the offending value named")
    void malformedDeniedEntry() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> configuration.capabilityPolicy(properties(List.of("shell"), List.of(), List.of())));
        assertTrue(failure.getMessage().contains("'shell'"));
    }

    @Test
    @DisplayName("per-tool egress and rate limits reach the policy, keyed by capability")
    void perToolLimits() {
        CapabilityPolicy policy = configuration.capabilityPolicy(properties(
                List.of(), List.of(), List.of(),
                java.util.Map.of("builtin.http_fetch", "api.github.com, *.example.com"),
                java.util.Map.of("builtin.shell", "5/1m")));

        assertEquals(java.util.Set.of("api.github.com", "*.example.com"),
                policy.toolEgress().get(CapabilityId.builtin("http_fetch")));
        assertEquals(io.jclaw.domain.policy.RateLimit.parse("5/1m"),
                policy.rateLimits().get(CapabilityId.builtin("shell")));

        assertThrows(IllegalArgumentException.class, () -> configuration.capabilityPolicy(properties(
                List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of("builtin.shell", "lots"))));
    }

    @Test
    @DisplayName("egress lists reach the guard")
    void egressLists() {
        EgressGuard guard = configuration.egressGuard(
                properties(List.of(), List.of("api.example.com", "*.trusted.example"), List.of("bad.example")));

        assertEquals(java.util.Set.of("api.example.com", "*.trusted.example"),
                java.util.Set.copyOf(guard.allowlistedHosts()));
        assertEquals(List.of("bad.example"), guard.denylistedHosts());
        assertEquals("host_not_in_allowlist",
                ((io.jclaw.contracts.Result.Err<?, String>) guard.check("https://other.example/")).error());

        EgressGuard open = configuration.egressGuard(properties(List.of(), List.of(""), List.of("")));
        assertTrue(open.allowlistedHosts().isEmpty(), "a blank default must not lock egress down");
    }
}
