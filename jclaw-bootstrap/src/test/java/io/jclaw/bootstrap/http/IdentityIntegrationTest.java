// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.http;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.bootstrap.identity.OidcLogin;
import io.jclaw.bootstrap.observability.Telemetry;
import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.ports.event.EventLog;
import io.jclaw.ports.identity.Role;
import io.jclaw.ports.identity.SessionStore;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.thread.ThreadService;
import io.jclaw.ports.turn.RunStore;
import io.jclaw.adapter.out.model.mock.MockModelProvider;
import io.jclaw.adapter.out.persistence.approval.JsonlApprovalStore;
import io.jclaw.adapter.out.persistence.identity.JsonlSessionStore;
import io.jclaw.adapter.out.persistence.jsonl.JsonlFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signing in, what a session is worth, and what a role stops you doing. The provider is a local
 * server speaking the parts of OpenID Connect the code flow actually uses.
 */
@SpringBootTest
class IdentityIntegrationTest {

    private static final String CLIENT_ID = "jclaw-test";
    private static final String CLIENT_SECRET = "client-secret-value";

    private static Path workspace;
    private static HttpServer provider;
    private static JclawHttpServer server;
    private static String issuer;

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-identity-it");
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + provider.getAddress().getPort();

        provider.createContext("/.well-known/openid-configuration", exchange -> respond(exchange, 200,
                "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/authorize\","
                        + "\"token_endpoint\":\"" + issuer + "/token\"}"));
        provider.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!form.contains("code_verifier=") || !form.contains("client_secret=" + CLIENT_SECRET)) {
                respond(exchange, 400, "{\"error\":\"invalid_request\"}");
                return;
            }
            respond(exchange, 200, "{\"access_token\":\"a\",\"id_token\":\"" + idToken() + "\"}");
        });
        provider.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** An id token as the code flow delivers it: claims that matter, over an authenticated channel. */
    private static String idToken() {
        String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64("{\"iss\":\"" + issuer + "\",\"aud\":\"" + CLIENT_ID + "\","
                + "\"sub\":\"user-1\",\"email\":\"alice@example.com\",\"exp\":"
                + (System.currentTimeMillis() / 1000 + 600) + "}");
        return header + "." + payload + ".signature-not-checked-in-the-code-flow";
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    static void stop() {
        provider.stop(0);
        if (server != null) {
            server.stop();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("done");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired RunStore runs;
    @Autowired EventLog events;
    @Autowired ThreadService threads;
    @Autowired JsonlApprovalStore approvals;
    @Autowired Telemetry telemetry;
    @Autowired Clock clock;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private String base;

    private HttpResponse<String> call(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        request = method.equals("GET") ? request.GET()
                : request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> response) {
        return mapper.readValue(response.body(), Map.class);
    }

    @Test
    @DisplayName("a session names a person, carries a role, expires, and can be revoked")
    void sessionsAndRoles() throws Exception {
        SessionStore sessions = new JsonlSessionStore(
                new JsonlFile(workspace.resolve("sessions.jsonl")), clock);
        OidcLogin login = new OidcLogin(issuer, CLIENT_ID, clock);

        server = new JclawHttpServer(runtime, runs, events, threads, approvals, clock,
                Optional.of("op-token"), Map.of("bot", "bot-token"), "mock-model", telemetry);
        server.withIdentity(sessions, Map.of("watcher", Role.VIEWER, "bot", Role.MEMBER),
                login, () -> Optional.of(CLIENT_SECRET), "http://127.0.0.1:1/login/callback");
        server.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + server.port();

        // Only an operator may hand out access.
        assertEquals(403, call("POST", "/login", "bot-token", "{\"user\":\"x\"}").statusCode());
        var minted = json(call("POST", "/login", "op-token",
                "{\"user\":\"watcher\",\"role\":\"VIEWER\",\"ttl\":\"PT1H\"}"));
        String viewerToken = String.valueOf(minted.get("token"));
        assertEquals("VIEWER", minted.get("role"));

        // The session authenticates, and the role decides what it is worth.
        assertEquals(200, call("GET", "/health", viewerToken, null).statusCode());
        var refused = call("POST", "/threads/work/turns", viewerToken, "{\"text\":\"go\"}");
        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("viewer"), refused.body());
        assertEquals(403, call("POST", "/v1/chat/completions", viewerToken,
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").statusCode());

        var member = json(call("POST", "/login", "op-token", "{\"user\":\"alice\",\"role\":\"MEMBER\"}"));
        String memberToken = String.valueOf(member.get("token"));
        assertEquals(202, call("POST", "/threads/work/turns", memberToken, "{\"text\":\"go\"}").statusCode());
        assertEquals("alice", runs.byStatus(io.jclaw.ports.turn.TurnStatus.QUEUED, 10).stream()
                .filter(r -> r.scope().thread().value().equals("alice:work"))
                .findFirst().orElseThrow().scope().tenant());

        // Only the hash is stored, so the file cannot be read back into access.
        String stored = Files.readString(workspace.resolve("sessions.jsonl"));
        assertFalse(stored.contains(memberToken), "a session store holding live tokens is a key ring");
        assertTrue(stored.contains("\"user\":\"alice\""));

        // Sessions end.
        assertEquals(200, call("POST", "/logout", memberToken, null).statusCode());
        assertEquals(401, call("GET", "/health", memberToken, null).statusCode());
        assertEquals(401, call("GET", "/health", "not-a-token", null).statusCode());

        assertTrue(sessions.active().stream().noneMatch(s -> s.user().equals("alice")));
        assertEquals(1, sessions.revokeAllFor("watcher"));
        assertEquals(401, call("GET", "/health", viewerToken, null).statusCode());
    }

    @Test
    @DisplayName("the OIDC code flow signs someone in, and refuses a replayed or unknown state")
    void oidcCodeFlow() throws Exception {
        SessionStore sessions = new JsonlSessionStore(
                new JsonlFile(workspace.resolve("oidc-sessions.jsonl")), clock);
        OidcLogin login = new OidcLogin(issuer, CLIENT_ID, clock);

        var discovered = login.discover().orElseThrow();
        assertEquals(issuer + "/authorize", discovered.authorization());
        assertEquals(issuer + "/token", discovered.token());

        String redirect = "http://127.0.0.1:9/login/callback";
        String url = login.start(redirect).orElseThrow();
        assertTrue(url.startsWith(issuer + "/authorize?"), url);
        assertTrue(url.contains("code_challenge_method=S256") && url.contains("response_type=code"), url);
        assertEquals(1, login.pendingLogins());

        String state = URI.create(url).getQuery().lines()
                .flatMap(q -> java.util.Arrays.stream(q.split("&")))
                .filter(p -> p.startsWith("state="))
                .map(p -> java.net.URLDecoder.decode(p.substring("state=".length()), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();

        var identity = login.complete("the-code", state, CLIENT_SECRET).orElseThrow();
        assertEquals("user-1", identity.subject());
        assertEquals(Optional.of("alice@example.com"), identity.email());
        assertEquals("alice", identity.user(), "the session is for the person, not the opaque subject");
        assertEquals(0, login.pendingLogins(), "the state is spent whether or not it worked");

        // The same state cannot be used twice, and an invented one is not honoured.
        assertTrue(login.complete("the-code", state, CLIENT_SECRET).isErr());
        assertTrue(login.complete("the-code", "invented", CLIENT_SECRET).isErr());

        // A wrong client secret fails at the provider, not silently here.
        String second = login.start(redirect).orElseThrow();
        String secondState = java.util.Arrays.stream(URI.create(second).getQuery().split("&"))
                .filter(p -> p.startsWith("state="))
                .map(p -> java.net.URLDecoder.decode(p.substring("state=".length()), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
        assertEquals("token_exchange_http_400",
                login.complete("the-code", secondState, "wrong-secret").errorAsOptional().orElseThrow());

        // Two logins never produce the same state.
        assertNotEquals(state, secondState);
        assertEquals(List.of(), sessions.active());
    }
}
