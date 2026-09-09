package io.jclaw.app.identity;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityId;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signing in through an OpenID Connect provider, authorization code flow with PKCE.
 *
 * <p>Three steps. Discovery reads the provider's metadata document once to learn where its
 * authorization and token endpoints are, so nothing here hard-codes a vendor. {@link #start}
 * mints a state and a PKCE verifier and returns the URL to send the browser to. {@link #complete}
 * exchanges the returned code for tokens, over a direct back-channel call to the token endpoint.
 *
 * <p>The id token's signature is <em>not</em> verified, and that is a deliberate, bounded choice
 * rather than an omission. In the code flow the token arrives over TLS on a connection this
 * process opened to the discovered token endpoint, authenticated by the client secret, so the
 * channel already establishes provenance; the OIDC specification says signature validation may be
 * skipped in exactly this case. What is checked is what the channel cannot tell us: the issuer,
 * the audience, and the expiry. An implicit or hybrid flow would need real signature checking,
 * which is why neither is supported here.
 *
 * <p>State is held in memory and expires. Losing it on restart costs a half-finished login, and
 * keeping it durable would mean writing a PKCE verifier to disk for no gain.
 */
public final class OidcLogin {

    /** The capability an OIDC client secret must be bound to in the vault. */
    public static final CapabilityId LOGIN = CapabilityId.of("identity.login");

    private static final Duration EXCHANGE_WINDOW = Duration.ofMinutes(10);

    /** A login in progress: what was sent, waiting for the browser to come back. */
    private record Pending(String verifier, String redirectUri, Instant expiresAt) { }

    /** The provider's endpoints, as discovered. */
    public record Endpoints(String authorization, String token, String issuer) {
        public Endpoints {
            Objects.requireNonNull(authorization, "authorization");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(issuer, "issuer");
        }
    }

    /** Who signed in. */
    public record Identity(String subject, Optional<String> email) {
        public Identity {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(email, "email");
        }

        /** The user name a session is issued for: the email's local part when there is one. */
        public String user() {
            return email.filter(address -> address.indexOf('@') > 0)
                    .map(address -> address.substring(0, address.indexOf('@')))
                    .orElse(subject);
        }
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final String issuer;
    private final String clientId;
    private volatile Endpoints endpoints;

    public OidcLogin(String issuer, String clientId, Clock clock) {
        this.issuer = Objects.requireNonNull(issuer, "issuer").replaceAll("/+$", "");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Reads the provider's metadata document, once, and remembers it. */
    @SuppressWarnings("unchecked")
    public Result<Endpoints, String> discover() {
        Endpoints known = endpoints;
        if (known != null) {
            return Result.ok(known);
        }
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(
                            URI.create(issuer + "/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Result.err("provider_unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("interrupted");
        }
        if (response.statusCode() >= 400) {
            return Result.err("discovery_http_" + response.statusCode());
        }
        Map<String, Object> document;
        try {
            document = mapper.readValue(response.body(), Map.class);
        } catch (RuntimeException e) {
            return Result.err("discovery_malformed");
        }
        Object authorization = document.get("authorization_endpoint");
        Object token = document.get("token_endpoint");
        if (!(authorization instanceof String) || !(token instanceof String)) {
            return Result.err("discovery_incomplete");
        }
        // The issuer in the document is authoritative for the check on the id token later.
        Endpoints discovered = new Endpoints((String) authorization, (String) token,
                String.valueOf(document.getOrDefault("issuer", issuer)));
        endpoints = discovered;
        return Result.ok(discovered);
    }

    /**
     * Begins a login.
     *
     * @return the URL to send the browser to, carrying the state that {@link #complete} expects
     */
    public Result<String, String> start(String redirectUri) {
        Objects.requireNonNull(redirectUri, "redirectUri");
        Result<Endpoints, String> discovered = discover();
        if (discovered.isErr()) {
            return Result.err(discovered.errorAsOptional().orElse("discovery_failed"));
        }
        expireStale();

        String state = randomUrlSafe();
        String verifier = randomUrlSafe();
        pending.put(state, new Pending(verifier, redirectUri, clock.instant().plus(EXCHANGE_WINDOW)));

        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        String url = discovered.orElseThrow().authorization()
                + (discovered.orElseThrow().authorization().indexOf('?') >= 0 ? "&" : "?")
                + "response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode("openid email profile")
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256";
        return Result.ok(url);
    }

    /**
     * Finishes a login by exchanging the code.
     *
     * <p>The state is consumed whether or not the exchange succeeds, so a code cannot be replayed
     * against the same login attempt.
     */
    @SuppressWarnings("unchecked")
    public Result<Identity, String> complete(String code, String state, String clientSecret) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(clientSecret, "clientSecret");
        expireStale();
        Pending started = pending.remove(state);
        if (started == null) {
            return Result.err("unknown_or_expired_state");
        }
        Result<Endpoints, String> discovered = discover();
        if (discovered.isErr()) {
            return Result.err(discovered.errorAsOptional().orElse("discovery_failed"));
        }

        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(started.redirectUri())
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&code_verifier=" + encode(started.verifier());
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(discovered.orElseThrow().token()))
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
            return Result.err("token_exchange_http_" + response.statusCode());
        }
        Map<String, Object> tokens;
        try {
            tokens = mapper.readValue(response.body(), Map.class);
        } catch (RuntimeException e) {
            return Result.err("token_response_malformed");
        }
        if (!(tokens.get("id_token") instanceof String idToken)) {
            return Result.err("no_id_token");
        }
        return claims(idToken).flatMap(claims -> verify(claims, discovered.orElseThrow()));
    }

    @SuppressWarnings("unchecked")
    private Result<Map<String, Object>, String> claims(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return Result.err("id_token_malformed");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            return Result.ok(mapper.readValue(payload, Map.class));
        } catch (RuntimeException e) {
            return Result.err("id_token_malformed");
        }
    }

    private Result<Identity, String> verify(Map<String, Object> claims, Endpoints discovered) {
        if (!discovered.issuer().equals(String.valueOf(claims.get("iss")))) {
            return Result.err("issuer_mismatch");
        }
        Object audience = claims.get("aud");
        boolean forUs = clientId.equals(String.valueOf(audience))
                || (audience instanceof java.util.List<?> list && list.contains(clientId));
        if (!forUs) {
            return Result.err("audience_mismatch");
        }
        if (!(claims.get("exp") instanceof Number exp)
                || Instant.ofEpochSecond(exp.longValue()).isBefore(clock.instant())) {
            return Result.err("id_token_expired");
        }
        Object subject = claims.get("sub");
        if (!(subject instanceof String name) || name.isBlank()) {
            return Result.err("no_subject");
        }
        return Result.ok(new Identity(name,
                Optional.ofNullable(claims.get("email")).map(String::valueOf).filter(e -> !e.isBlank())));
    }

    private void expireStale() {
        Instant now = clock.instant();
        pending.values().removeIf(started -> started.expiresAt().isBefore(now));
    }

    private String randomUrlSafe() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Logins started and not yet finished, for diagnostics. */
    public int pendingLogins() {
        expireStale();
        return pending.size();
    }

    /** The endpoints, when discovery has run. */
    public Optional<Endpoints> known() {
        return Optional.ofNullable(endpoints);
    }

    /** Exposed for wiring: the metadata map a caller may want to log. Never includes a secret. */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("issuer", issuer);
        out.put("clientId", clientId);
        return out;
    }
}
