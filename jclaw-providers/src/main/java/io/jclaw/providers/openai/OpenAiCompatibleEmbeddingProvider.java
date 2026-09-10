// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.providers.openai;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.memory.Embedding;
import io.jclaw.contracts.memory.EmbeddingProvider;
import io.jclaw.contracts.model.ModelProvider.ProviderFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.Optional;

/**
 * Embeddings from any server speaking the OpenAI {@code /embeddings} API.
 *
 * <p>The same convergence that lets one chat adapter serve OpenAI, OpenRouter, Ollama, vLLM, and
 * LM Studio holds for embeddings: all of them accept {@code {"model", "input"}} and return
 * {@code data[].embedding}. Anthropic has no embeddings endpoint, so this adapter is the only one;
 * an operator on Claude for chat typically pairs it with Ollama ({@code nomic-embed-text}) or
 * OpenAI ({@code text-embedding-3-small}) for vectors.
 *
 * <p>Same rules as the chat adapter, for the same reasons: HTTP/1.1 pinned (h2c upgrade headers
 * break llhttp-based servers), credentials resolved per request so construction never fails,
 * failures sanitized at this boundary, and the base URL exempt from the egress guard because it
 * is operator configuration rather than a model-controlled URL.
 */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingProvider.class);

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** Refuse absurd inputs before they cost a request; embedding models truncate far below this. */
    private static final int MAX_INPUT_CHARS = 32_000;

    private final String id;
    private final String model;
    private final URI endpoint;
    private final Optional<String> apiKey;
    private final Optional<String> credentialHint;
    private final Map<String, String> extraHeaders;
    private final HttpClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenAiCompatibleEmbeddingProvider(
            String id,
            String baseUrl,
            String model,
            Optional<String> apiKey,
            Optional<String> credentialHint,
            Map<String, String> extraHeaders,
            HttpClient client) {

        this.id = Objects.requireNonNull(id, "id");
        this.model = Objects.requireNonNull(model, "model");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.credentialHint = credentialHint == null ? Optional.empty() : credentialHint;
        this.extraHeaders = Map.copyOf(Objects.requireNonNull(extraHeaders, "extraHeaders"));
        this.client = Objects.requireNonNull(client, "client");
        if (model.isBlank()) {
            throw new IllegalArgumentException("embedding model must not be blank");
        }
        String trimmed = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.endpoint = URI.create(trimmed + "/embeddings");
    }

    /** See {@code OpenAiCompatibleModelProvider#defaultClient()} for why HTTP/1.1 is pinned. */
    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /** OpenAI itself. */
    public static OpenAiCompatibleEmbeddingProvider openai(String model, String apiKey) {
        return new OpenAiCompatibleEmbeddingProvider(
                "openai", "https://api.openai.com/v1", model,
                Optional.ofNullable(emptyToNull(apiKey)), Optional.of("OPENAI_API_KEY"),
                Map.of(), defaultClient());
    }

    /** OpenRouter, with the same optional attribution headers as the chat adapter. */
    public static OpenAiCompatibleEmbeddingProvider openrouter(
            String model, String apiKey, String baseUrl, String referer, String title) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (referer != null && !referer.isBlank()) {
            headers.put("HTTP-Referer", referer);
        }
        if (title != null && !title.isBlank()) {
            headers.put("X-Title", title);
        }
        return new OpenAiCompatibleEmbeddingProvider(
                "openrouter",
                baseUrl == null || baseUrl.isBlank()
                        ? OpenAiCompatibleModelProvider.OPENROUTER_BASE_URL : baseUrl,
                model,
                Optional.ofNullable(emptyToNull(apiKey)), Optional.of("OPENROUTER_API_KEY"),
                headers, defaultClient());
    }

    /** A local Ollama daemon; needs no credentials. */
    public static OpenAiCompatibleEmbeddingProvider ollama(String baseUrl, String model) {
        return new OpenAiCompatibleEmbeddingProvider(
                "ollama", baseUrl, model, Optional.empty(), Optional.empty(), Map.of(), defaultClient());
    }

    /** Any other local OpenAI-compatible server; a key is sent when given, never required. */
    public static OpenAiCompatibleEmbeddingProvider local(String baseUrl, String model, String apiKey) {
        return new OpenAiCompatibleEmbeddingProvider(
                "local", baseUrl, model, Optional.ofNullable(emptyToNull(apiKey)), Optional.empty(),
                Map.of(), defaultClient());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Result<Embedding, ProviderFailure> embed(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, "nothing to embed"));
        }
        if (text.length() > MAX_INPUT_CHARS) {
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, "input too long to embed"));
        }
        if (apiKey.isEmpty() && credentialHint.isPresent()) {
            log.debug("{}: no credential ({} unset) -> AUTH failure", id, credentialHint.get());
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.AUTH, credentialHint.get() + " is not set"));
        }

        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("model", model);
        wire.put("input", text);
        String body = mapper.writeValueAsString(wire);

        HttpRequest.Builder http = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        apiKey.ifPresent(key -> http.header("Authorization", "Bearer " + key));
        extraHeaders.forEach(http::header);

        log.debug("{}: POST {} model {} ({} chars)", id, endpoint, model, text.length());
        HttpResponse<String> response;
        try {
            response = client.send(http.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.debug("{}: transport failure ({})", id, e.getClass().getSimpleName());
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.TRANSPORT, "transport failure"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.TRANSPORT, "interrupted"));
        }

        int status = response.statusCode();
        log.debug("{}: HTTP {} ({} bytes)", id, status, response.body().length());
        if (status >= 400) {
            return Result.err(OpenAiCompatibleFailures.classify(status, response.body(), mapper));
        }
        try {
            Embedding embedding = fromWireResponse(response.body());
            log.debug("{}: embedded {} dimension(s) with {}", id, embedding.dimensions(), model);
            return Result.ok(embedding);
        } catch (RuntimeException e) {
            log.debug("{}: unparseable response ({})", id, e.getClass().getSimpleName());
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.UPSTREAM, "unparseable response"));
        }
    }

    @SuppressWarnings("unchecked")
    private Embedding fromWireResponse(String body) {
        Map<String, Object> parsed = mapper.readValue(body, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("no data in response");
        }
        List<Object> raw = (List<Object>) data.get(0).get("embedding");
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("no embedding in response");
        }
        float[] values = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            values[i] = ((Number) raw.get(i)).floatValue();
        }
        return new Embedding(model, values);
    }
}
