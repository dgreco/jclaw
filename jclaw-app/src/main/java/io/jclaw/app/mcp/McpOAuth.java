package io.jclaw.app.mcp;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.mcp.McpServerStore;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.domain.secret.SecretInjection;
import io.jclaw.kernel.guard.EgressGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OAuth 2.1 access tokens for a remote MCP server, by the client-credentials grant.
 *
 * <p>The MCP specification describes the authorization-code flow with PKCE, which assumes a
 * browser and a person to consent. An agent has neither: a headless worker that parked a run
 * waiting for someone to click "allow" would be worse than a static token, not better. Client
 * credentials is the grant that fits — the operator registers jclaw as a confidential client
 * once, and the long-lived secret they hold is exchanged for short-lived access tokens.
 *
 * <p>That exchange is the whole benefit, and it is real: the credential that travels to the MCP
 * server on every request expires in minutes, so an intercepted one is worth little, and it can
 * be revoked at the authorization server without touching jclaw's configuration.
 *
 * <p>The client secret is a vault entry bound to {@code mcp.connect} and the token endpoint's
 * host, checked on every mint. A secret an operator bound to one authorization server cannot be
 * spent at another, and one bound to a tool cannot be spent signing in at all.
 *
 * <p>A token is cached until shortly before it expires. The margin is not politeness: a token
 * that expires in flight produces a 401 the caller reads as a broken server rather than as a
 * clock problem.
 */
public final class McpOAuth {

    private static final Logger log = LoggerFactory.getLogger(McpOAuth.class);

    /** The capability an MCP client secret must be bound to. */
    public static final CapabilityId CONNECT = CapabilityId.of("mcp.connect");

    /** Renew this long before expiry, so a token never expires mid-request. */
    private static final Duration MARGIN = Duration.ofSeconds(60);

    /** A minted token and when it stops being usable. */
    private record Token(String value, Instant usableUntil) { }

    private final SecretVault vault;
    private final EgressGuard egress;
    private final Clock clock;
    private final HttpClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<Token> cached = new AtomicReference<>();
    private final AtomicReference<String> discovered = new AtomicReference<>();

    private final McpServerStore.OAuth config;
    private final String serverUrl;

    public McpOAuth(McpServerStore.OAuth config, String serverUrl,
            SecretVault vault, EgressGuard egress, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.egress = Objects.requireNonNull(egress, "egress");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * A current {@code Authorization} header value, minting one if the cached token is near
     * expiry.
     *
     * <p>Returns empty rather than throwing when the mint fails. The transport then sends an
     * unauthenticated request and the server answers 401, which is a better failure than a stack
     * trace: the operator sees the server refusing them, and the log line here says why.
     */
    public Optional<String> header() {
        Token token = cached.get();
        if (token != null && clock.instant().isBefore(token.usableUntil())) {
            return Optional.of("Bearer " + token.value());
        }
        Result<Token, String> minted = mint();
        if (minted.isErr()) {
            log.debug("mcp oauth: no token for {} ({})", serverUrl, minted.errorAsOptional().orElse("?"));
            return Optional.empty();
        }
        cached.set(minted.orElseThrow());
        return Optional.of("Bearer " + minted.orElseThrow().value());
    }

    /** Drops the cached token, so the next request mints a fresh one. */
    public void forget() {
        cached.set(null);
    }

    @SuppressWarnings("unchecked")
    private Result<Token, String> mint() {
        Result<String, String> endpoint = tokenEndpoint();
        if (endpoint.isErr()) {
            return Result.err(endpoint.errorAsOptional().orElse("no_token_endpoint"));
        }
        Result<URI, String> checked = egress.check(endpoint.orElseThrow());
        if (checked.isErr()) {
            return Result.err("token_endpoint_" + checked.errorAsOptional().orElse("denied"));
        }
        URI tokenUri = checked.orElseThrow();

        Result<String, String> secret = clientSecret(tokenUri);
        if (secret.isErr()) {
            return Result.err(secret.errorAsOptional().orElse("client_secret_unavailable"));
        }

        String form = "grant_type=client_credentials"
                + "&client_id=" + encode(config.clientId())
                + "&client_secret=" + encode(secret.orElseThrow())
                + (config.scope().isBlank() ? "" : "&scope=" + encode(config.scope()));
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(tokenUri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Result.err("token_endpoint_unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("interrupted");
        }
        if (response.statusCode() >= 400) {
            // The body can echo the client secret back; only the status is safe to report.
            return Result.err("token_http_" + response.statusCode());
        }
        Map<String, Object> body;
        try {
            body = mapper.readValue(response.body(), Map.class);
        } catch (RuntimeException e) {
            return Result.err("token_response_malformed");
        }
        if (!(body.get("access_token") instanceof String access) || access.isBlank()) {
            return Result.err("no_access_token");
        }
        // A server that omits expires_in is saying nothing about lifetime, not that the token is
        // eternal. Five minutes is short enough to be safe and long enough not to mint per call.
        long seconds = body.get("expires_in") instanceof Number expires ? expires.longValue() : 300;
        Instant usableUntil = clock.instant().plusSeconds(Math.max(1, seconds)).minus(MARGIN);
        log.debug("mcp oauth: token for {} valid ~{}s", serverUrl, seconds);
        return Result.ok(new Token(access, usableUntil));
    }

    /** The configured token endpoint, or one discovered from the server's authorization metadata. */
    @SuppressWarnings("unchecked")
    private Result<String, String> tokenEndpoint() {
        if (!config.tokenUrl().isBlank()) {
            return Result.ok(config.tokenUrl());
        }
        String known = discovered.get();
        if (known != null) {
            return Result.ok(known);
        }
        // RFC 8414: the authorization server's metadata sits at a well-known path on its origin.
        URI server;
        try {
            server = URI.create(serverUrl);
        } catch (RuntimeException e) {
            return Result.err("server_url_malformed");
        }
        String origin = server.getScheme() + "://" + server.getAuthority();
        Result<URI, String> checked = egress.check(origin + "/.well-known/oauth-authorization-server");
        if (checked.isErr()) {
            return Result.err("discovery_" + checked.errorAsOptional().orElse("denied"));
        }
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(checked.orElseThrow())
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Result.err("discovery_unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("interrupted");
        }
        if (response.statusCode() >= 400) {
            return Result.err("discovery_http_" + response.statusCode());
        }
        Map<String, Object> metadata;
        try {
            metadata = mapper.readValue(response.body(), Map.class);
        } catch (RuntimeException e) {
            return Result.err("discovery_malformed");
        }
        if (!(metadata.get("token_endpoint") instanceof String endpoint) || endpoint.isBlank()) {
            return Result.err("discovery_has_no_token_endpoint");
        }
        discovered.set(endpoint);
        return Result.ok(endpoint);
    }

    /** Leases the client secret, refusing one not bound to this capability and this host. */
    private Result<String, String> clientSecret(URI tokenUri) {
        SecretVault.SecretName name;
        try {
            name = new SecretVault.SecretName(config.clientSecretName());
        } catch (IllegalArgumentException e) {
            return Result.err("client_secret_name_invalid");
        }
        Optional<SecretVault.Lease> lease = vault.lease(name);
        if (lease.isEmpty()) {
            return Result.err("client_secret_unknown");
        }
        String host = tokenUri.getHost();
        Optional<String> refusal = SecretInjection.refuse(
                lease.get().info().binding(), CONNECT, Set.of(host == null ? "" : host));
        return refusal.<Result<String, String>>map(Result::err)
                .orElseGet(() -> Result.ok(lease.get().value()));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
