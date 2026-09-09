package io.jclaw.kernel.guard;

import io.jclaw.contracts.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configurable lists. Address-family checks need DNS, so these tests use hosts whose
 * verdict is decided before resolution, and {@code localhost} for the one case that must still
 * resolve and be refused.
 */
class EgressGuardTest {

    private static String reason(Result<URI, String> result) {
        return ((Result.Err<URI, String>) result).error();
    }

    @Test
    @DisplayName("a denylist entry is refused before anything else is checked")
    void denylist() {
        EgressGuard guard = EgressGuard.publicOnly().withDenylist(Set.of("Evil.example"));

        assertEquals("host_denied", reason(guard.check("https://evil.example/x")),
                "matching is case-insensitive");
    }

    @Test
    @DisplayName("with an allowlist, hosts outside it are refused")
    void allowlist() {
        EgressGuard guard = EgressGuard.publicOnly().withAllowlist(Set.of("api.example.com"));

        assertEquals("host_not_in_allowlist", reason(guard.check("https://other.example.com/")));
        assertEquals(Set.of("api.example.com"), Set.copyOf(guard.allowlistedHosts()));
    }

    @Test
    @DisplayName("a wildcard entry covers subdomains but not the bare domain")
    void wildcard() {
        EgressGuard guard = EgressGuard.allowingPrivateNetworks()
                .withAllowlist(Set.of("*.example.com"));

        assertTrue(guard.check("https://api.example.com/").isOk());
        assertTrue(guard.check("https://deep.api.example.com/").isOk());
        assertEquals("host_not_in_allowlist", reason(guard.check("https://example.com/")));
        assertEquals("host_not_in_allowlist", reason(guard.check("https://notexample.com/")));
    }

    @Test
    @DisplayName("an allowlist never re-opens private networks or the metadata hosts")
    void allowlistDoesNotOverrideHardDenials() {
        EgressGuard guard = EgressGuard.publicOnly()
                .withAllowlist(Set.of("localhost", "169.254.169.254"));

        assertEquals("host_denied", reason(guard.check("http://169.254.169.254/latest/meta-data/")),
                "the metadata endpoint is denied by name regardless of the allowlist");
        assertEquals("private_address_denied", reason(guard.check("http://localhost:6379/")),
                "an allowlisted host still has to resolve to a public address");
    }

    @Test
    @DisplayName("the denylist wins over the allowlist")
    void denyBeatsAllow() {
        EgressGuard guard = EgressGuard.allowingPrivateNetworks()
                .withAllowlist(Set.of("*.example.com"))
                .withDenylist(Set.of("secret.example.com"));

        assertEquals("host_denied", reason(guard.check("https://secret.example.com/")));
        assertTrue(guard.check("https://public.example.com/").isOk());
    }
}
