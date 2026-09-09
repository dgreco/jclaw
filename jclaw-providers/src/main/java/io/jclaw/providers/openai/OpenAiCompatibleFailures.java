package io.jclaw.providers.openai;

import io.jclaw.contracts.model.ModelProvider.ProviderFailure;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Maps OpenAI-compatible HTTP failures to the sanitized failure vocabulary.
 *
 * <p>Shared by the chat and embeddings adapters so the two never drift: a 429 is rate limiting on
 * both endpoints, and a 400's {@code error.message} is the most useful thing available for both.
 */
final class OpenAiCompatibleFailures {

    private OpenAiCompatibleFailures() {
    }

    /**
     * Maps an HTTP status to a failure category.
     *
     * <p>For a rejected request the provider's own {@code error.message} is included. It describes
     * the shape of the request <em>we</em> sent, a bad tool name or an unsupported parameter, and
     * is by far the most useful thing available at that moment. Only extracted for 4xx
     * invalid-request responses, bounded by {@link ProviderFailure}, and redacted downstream
     * before it reaches the event log.
     */
    static ProviderFailure classify(int status, String body, JsonMapper mapper) {
        return switch (status) {
            case 401, 403 -> ProviderFailure.of(ProviderFailure.Kind.AUTH, "credentials rejected");
            case 404 -> ProviderFailure.of(
                    ProviderFailure.Kind.UNKNOWN_MODEL, "model or endpoint not found");
            case 429 -> ProviderFailure.of(ProviderFailure.Kind.RATE_LIMIT, "rate limited");
            case 400, 422 -> ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, errorMessage(body, mapper));
            default -> ProviderFailure.of(ProviderFailure.Kind.UPSTREAM, "http " + status);
        };
    }

    /** Pulls {@code error.message} out of a provider error body, falling back to a bare category. */
    @SuppressWarnings("unchecked")
    static String errorMessage(String body, JsonMapper mapper) {
        try {
            Map<String, Object> parsed = mapper.readValue(body, Map.class);
            if (parsed.get("error") instanceof Map<?, ?> error) {
                Object message = ((Map<String, Object>) error).get("message");
                if (message != null) {
                    return String.valueOf(message);
                }
            }
        } catch (RuntimeException e) {
            // Not JSON, or not the shape we expected.
        }
        return "request rejected";
    }
}
