package io.jclaw.app.config;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.kernel.capability.CapabilityPolicy;
import io.jclaw.kernel.guard.EgressGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private static JclawProperties properties(
            List<String> denied, List<String> allow, List<String> deny) {
        return properties(denied, allow, deny, java.util.Map.of(), java.util.Map.of());
    }

    private static JclawProperties properties(
            List<String> denied, List<String> allow, List<String> deny,
            java.util.Map<String, String> toolEgress, java.util.Map<String, String> toolRateLimits) {
        return new JclawProperties(
                Path.of("."),
                Path.of("build", "test-state"),
                "claude-opus-5",
                "mock",
                "https://api.openai.com/v1",
                "http://localhost:11434/v1",
                "",
                "https://openrouter.ai/api/v1",
                "",
                "jclaw",
                "interactive",
                false,
                25,
                500_000,
                200,
                100_000,
                "system",
                List.of(),
                "none",
                "",
                denied,
                allow,
                deny,
                "sanitize",
                true,
                1024,
                java.time.Duration.ofHours(24),
                toolEgress,
                toolRateLimits,
                false,
                java.time.Duration.ofDays(14),
                java.time.Duration.ofDays(30),
                java.time.Duration.ofDays(7),
                "",
                "host",
                "docker",
                "alpine:3.20",
                "none",
                "512m",
                "1",
                256);
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
