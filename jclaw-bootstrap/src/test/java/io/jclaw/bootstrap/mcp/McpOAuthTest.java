// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.mcp;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.ports.capability.CapabilityId;
import io.jclaw.ports.mcp.McpServerStore;
import io.jclaw.ports.secret.SecretVault;
import io.jclaw.ports.secret.SecretVault.Binding;
import io.jclaw.ports.secret.SecretVault.SecretName;
import io.jclaw.application.authority.guard.EgressGuard;
import io.jclaw.adapter.out.persistence.jsonl.JsonlFile;
import io.jclaw.adapter.out.persistence.secret.FileSecretVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client credentials: a long-lived secret the operator holds becomes short-lived tokens on the
 * wire, minted lazily, cached until they are nearly expired, and refused when the vault binding
 * does not name this endpoint.
 */
class McpOAuthTest {

    private static final String CLIENT_SECRET = "cs-0123456789abcdef";

    @TempDir Path dir;

    private HttpServer server;
    private String origin;
    private final AtomicInteger mints = new AtomicInteger();
    private final AtomicInteger expiresIn = new AtomicInteger(3600);
    private final List<String> tokenBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                tokenBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = ("{\"access_token\":\"tok-" + mints.incrementAndGet()
                    + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn.get() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/.well-known/oauth-authorization-server", exchange -> {
            byte[] body = ("{\"issuer\":\"" + origin + "\",\"token_endpoint\":\""
                    + origin + "/oauth/token\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SecretVault vault(Set<String> hosts) {
        SecretVault vault = new FileSecretVault(
                new JsonlFile(dir.resolve("secrets-" + hosts.hashCode() + ".jsonl")),
                new byte[32], Clock.systemUTC());
        vault.put(new SecretName("mcp-client"), CLIENT_SECRET,
                new Binding(CapabilityId.of("mcp.connect"), hosts));
        return vault;
    }

    private McpOAuth oauth(SecretVault vault, String tokenUrl) {
        return new McpOAuth(
                new McpServerStore.OAuth("jclaw-client", "mcp-client", tokenUrl, "mcp:read"),
                origin + "/mcp", vault, EgressGuard.allowingPrivateNetworks(), clock);
    }

    @Test
    @DisplayName("a token is minted once and reused until it is nearly expired")
    void mintsAndCaches() {
        McpOAuth oauth = oauth(vault(Set.of("127.0.0.1")), origin + "/oauth/token");

        assertEquals(Optional.of("Bearer tok-1"), oauth.header());
        assertEquals(Optional.of("Bearer tok-1"), oauth.header());
        assertEquals(1, mints.get(), "a valid token is not re-minted on every request");

        String body = tokenBodies.get(0);
        assertTrue(body.contains("grant_type=client_credentials"), body);
        assertTrue(body.contains("client_id=jclaw-client"), body);
        assertTrue(body.contains("client_secret=" + CLIENT_SECRET), body);
        assertTrue(body.contains("scope=mcp%3Aread"), body);

        // Past the refresh margin but before the nominal expiry: still refreshed, deliberately,
        // because a token that expires in flight reads as a broken server rather than a clock.
        now.set(now.get().plus(Duration.ofSeconds(3600).minusSeconds(30)));
        assertEquals(Optional.of("Bearer tok-2"), oauth.header());
        assertEquals(2, mints.get());
    }

    @Test
    @DisplayName("the token endpoint can be discovered from the server's metadata")
    void discoversTokenEndpoint() {
        McpOAuth oauth = oauth(vault(Set.of("127.0.0.1")), "");
        assertEquals(Optional.of("Bearer tok-1"), oauth.header());
        assertEquals(1, mints.get());

        // Discovery is done once; a second mint does not fetch the metadata again.
        oauth.forget();
        assertEquals(Optional.of("Bearer tok-2"), oauth.header());
        assertEquals(2, mints.get());
    }

    @Test
    @DisplayName("a secret bound elsewhere is refused, and no token is minted")
    void bindingIsChecked() {
        McpOAuth oauth = oauth(vault(Set.of("some.other.host")), origin + "/oauth/token");
        assertTrue(oauth.header().isEmpty(),
                "a secret bound to one authorization server cannot be spent at another");
        assertEquals(0, mints.get(), "the request never left the process");
    }

    @Test
    @DisplayName("a missing secret, or a guarded endpoint, is an empty header rather than a throw")
    void failuresDegrade() {
        SecretVault empty = new FileSecretVault(
                new JsonlFile(dir.resolve("empty.jsonl")), new byte[32], Clock.systemUTC());
        assertTrue(new McpOAuth(
                new McpServerStore.OAuth("c", "absent", origin + "/oauth/token", ""),
                origin + "/mcp", empty, EgressGuard.allowingPrivateNetworks(), clock)
                .header().isEmpty());

        // The default guard refuses loopback, so the token endpoint is never reached.
        assertTrue(new McpOAuth(
                new McpServerStore.OAuth("c", "mcp-client", origin + "/oauth/token", ""),
                origin + "/mcp", vault(Set.of("127.0.0.1")), EgressGuard.publicOnly(), clock)
                .header().isEmpty());
        assertEquals(0, mints.get());
    }

    @Test
    @DisplayName("a server that omits expires_in gets a short assumed lifetime, not an eternal one")
    void missingExpiryIsShort() throws IOException {
        server.removeContext("/oauth/token");
        server.createContext("/oauth/token", exchange -> {
            byte[] body = ("{\"access_token\":\"tok-" + mints.incrementAndGet() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        McpOAuth oauth = oauth(vault(Set.of("127.0.0.1")), origin + "/oauth/token");
        assertEquals(Optional.of("Bearer tok-1"), oauth.header());
        now.set(now.get().plus(Duration.ofMinutes(5)));
        assertEquals(Optional.of("Bearer tok-2"), oauth.header(),
                "silence about lifetime is not a claim of immortality");
    }
}
