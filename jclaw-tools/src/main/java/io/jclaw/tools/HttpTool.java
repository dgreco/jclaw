package io.jclaw.tools;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.HandlerError;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fetches a URL over HTTP(S).
 *
 * <p>Every URL is validated by the host egress guard before a connection is opened — the handler
 * has no way to reach the network except through {@link HandlerContext#checkEgress}, which is what
 * keeps SSRF policy in one place rather than duplicated per tool.
 *
 * <p>Request headers are optional and travel only to the host they were written for: a redirect
 * to another host is fetched without them, the way browsers drop {@code Authorization} across
 * origins. A vault secret substituted into a header therefore reaches the bound host and no
 * other, even if that host redirects.
 *
 * <p>Redirects are followed <b>normally, not automatically</b>. {@link HttpClient.Redirect#NEVER}
 * is deliberate: the JDK client would otherwise follow a 302 to {@code http://169.254.169.254/}
 * without consulting the guard, turning a validated request into an unvalidated one. Each hop is
 * re-checked here instead.
 */
public final class HttpTool implements CapabilityHandler {

    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
            "http_fetch",
            "Fetch a URL over HTTP or HTTPS and return the response body as text.",
            EffectClass.NETWORK,
            Schemas.object(
                    Schemas.properties(
                            "url", Schemas.string("Absolute http:// or https:// URL to fetch."),
                            "headers", Map.of(
                                    "type", "object",
                                    "description", "Optional request headers, name to value. "
                                            + "A vault secret goes here as {{secret:NAME}}.",
                                    "additionalProperties", Map.of("type", "string"))),
                    List.of("url")));

    private final HttpClient client;

    public HttpTool() {
        this(HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                // Never follow redirects implicitly: each hop must be re-validated by the guard.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public HttpTool(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
        String url = invocation.stringArg("url", "");
        if (url.isBlank()) {
            return Result.err(HandlerError.failed("url_required"));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (invocation.arguments().get("headers") instanceof Map<?, ?> given) {
            for (Map.Entry<?, ?> entry : given.entrySet()) {
                String name = String.valueOf(entry.getKey()).trim();
                if (name.isEmpty() || RESTRICTED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    return Result.err(HandlerError.failed("header_not_allowed"));
                }
                headers.put(name, String.valueOf(entry.getValue()));
            }
        }
        return fetchFollowing(url, headers, null, context, MAX_REDIRECTS);
    }

    /** Headers the client owns; a caller setting them would break or spoof the request. */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host", "content-length", "connection", "upgrade", "transfer-encoding", "user-agent");

    /** Fetches, re-validating the target on every redirect hop and dropping headers across hosts. */
    private Result<String, HandlerError> fetchFollowing(
            String url, Map<String, String> headers, String headersHost, HandlerContext context, int hopsLeft) {
        if (hopsLeft <= 0) {
            return Result.err(HandlerError.failed("too_many_redirects"));
        }
        return context.checkEgress(url).mapErr(HandlerError::denied).flatMap(uri -> {
            String host = uri.getHost() == null ? "" : uri.getHost();
            boolean sameHost = headersHost == null || headersHost.equalsIgnoreCase(host);
            HttpResponse<String> response;
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .header("User-Agent", "jclaw/0.1");
                if (sameHost) {
                    headers.forEach(request::header);
                }
                response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                return Result.err(HandlerError.failed("request_failed"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.err(HandlerError.failed("interrupted"));
            } catch (RuntimeException e) {
                return Result.err(HandlerError.failed("request_rejected"));
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse("");
                if (location.isBlank()) {
                    return Result.err(HandlerError.failed("redirect_without_location"));
                }
                // Resolve relative redirects against the current URI, then re-check egress.
                String next = uri.resolve(location).toString();
                return fetchFollowing(next, headers, headersHost == null ? host : headersHost, context, hopsLeft - 1);
            }
            if (status >= 400) {
                // Status only. A response body from a failed request is attacker-controlled text.
                return Result.err(HandlerError.failed("http_status_" + status));
            }

            String body = response.body();
            if (body.length() > MAX_BODY_BYTES) {
                body = body.substring(0, MAX_BODY_BYTES);
            }
            return Result.ok(body);
        });
    }
}
