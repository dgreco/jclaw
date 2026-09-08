package io.jclaw.providers.openai;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.model.ModelExchange.StopReason;
import io.jclaw.contracts.model.ModelExchange.ToolSpec;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.model.ToolNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Provider for any server speaking the OpenAI chat-completions API.
 *
 * <p>One adapter covers OpenAI itself, Ollama, vLLM, LM Studio, and most local inference servers,
 * because they all converged on the same wire format. Written against raw HTTP rather than an SDK:
 * there is no single official client for "OpenAI-compatible", and the surface used here is one
 * endpoint.
 *
 * <p><b>On egress policy.</b> This provider does not consult the egress guard, and that is
 * deliberate rather than an oversight. The guard exists to stop <em>model-controlled</em> URLs from
 * reaching internal addresses — the SSRF path is a prompt talking a tool into fetching
 * {@code 169.254.169.254}. A provider base URL is operator configuration, and pointing it at
 * {@code localhost:11434} for Ollama is the intended use, not an attack. Applying the guard here
 * would block the main local-inference use case while preventing nothing: an operator who can set
 * the base URL can already reach that address directly.
 */
public final class OpenAiCompatibleModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelProvider.class);

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** OpenRouter's OpenAI-compatible endpoint. */
    public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";

    private final String id;
    private final URI endpoint;
    private final Optional<String> apiKey;
    private final Optional<String> credentialHint;
    private final Map<String, String> extraHeaders;
    private final Predicate<String> supportsModel;
    private final HttpClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenAiCompatibleModelProvider(String id, String baseUrl, Optional<String> apiKey) {
        this(id, baseUrl, apiKey, Optional.empty(), Map.of(), notAnthropicShorthand(),
                defaultClient());
    }

    public OpenAiCompatibleModelProvider(
            String id, String baseUrl, Optional<String> apiKey, HttpClient client) {
        this(id, baseUrl, apiKey, Optional.empty(), Map.of(), notAnthropicShorthand(), client);
    }

    /**
     * @param credentialHint name of the environment variable this endpoint reads its key from,
     *                       used to produce an actionable error when the key is absent. Construction
     *                       never fails on a missing credential: a provider that throws while being
     *                       wired takes the whole application context down, including
     *                       {@code doctor} — the one command whose job is to report that the
     *                       credential is missing.
     * @param extraHeaders  gateway-specific headers sent on every request
     * @param supportsModel decides which model ids this endpoint will accept, so a failover chain
     *                      does not route an id the gateway cannot resolve
     */
    public OpenAiCompatibleModelProvider(
            String id,
            String baseUrl,
            Optional<String> apiKey,
            Optional<String> credentialHint,
            Map<String, String> extraHeaders,
            Predicate<String> supportsModel,
            HttpClient client) {

        this.id = Objects.requireNonNull(id, "id");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.credentialHint = credentialHint == null ? Optional.empty() : credentialHint;
        this.extraHeaders = Map.copyOf(Objects.requireNonNull(extraHeaders, "extraHeaders"));
        this.supportsModel = Objects.requireNonNull(supportsModel, "supportsModel");
        this.client = Objects.requireNonNull(client, "client");

        String trimmed = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.endpoint = URI.create(trimmed + "/chat/completions");
    }

    /**
     * HTTP/1.1, pinned deliberately.
     *
     * <p>Java's {@code HttpClient} defaults to HTTP/2, and on a plain {@code http://} URL that
     * means an h2c upgrade attempt: every request carries {@code Connection: Upgrade,
     * HTTP2-Settings} and {@code Upgrade: h2c}. The llhttp/http-parser family — which is what
     * uvicorn's httptools mode uses, i.e. vLLM and everything else on {@code uvicorn[standard]} —
     * pauses body parsing when it sees an Upgrade header, treating the bytes after the headers as
     * opaque upgrade data. The server then handles the request <em>without its body</em> and
     * FastAPI answers with the maximally confusing
     * {@code {'type': 'missing', 'loc': ('body',), 'msg': 'Field required'}}.
     *
     * <p>Pinning 1.1 removes the upgrade dance entirely and costs nothing here: every
     * chat-completions endpoint speaks HTTP/1.1, requests are sequential, and the TLS gateways
     * (OpenAI, OpenRouter) serve 1.1 without complaint. Verified against
     * {@code uvicorn --http httptools}: default client fails exactly as above, this one works.
     */
    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * The permissive default: anything that is not a bare Anthropic model id.
     *
     * <p>A gateway or local server may host any name, so an allowlist would reject legitimate
     * models it has never heard of. Bare {@code claude-*} ids are excluded because those belong to
     * the first-party Anthropic adapter, which speaks the real Messages API.
     */
    private static Predicate<String> notAnthropicShorthand() {
        return model -> !model.startsWith("claude-");
    }

    /** OpenAI itself. */
    public static OpenAiCompatibleModelProvider openai(String apiKey) {
        return new OpenAiCompatibleModelProvider(
                "openai", "https://api.openai.com/v1", Optional.ofNullable(emptyToNull(apiKey)),
                Optional.of("OPENAI_API_KEY"), Map.of(), notAnthropicShorthand(), defaultClient());
    }

    /** A local Ollama daemon, which needs no credentials. */
    public static OpenAiCompatibleModelProvider ollama(String baseUrl) {
        return new OpenAiCompatibleModelProvider("ollama", baseUrl, Optional.empty());
    }

    /**
     * Any local OpenAI-compatible inference server: LM Studio, vLLM, llama.cpp's server,
     * LocalAI, text-generation-webui, and the rest of the family that converged on the same
     * endpoint.
     *
     * <p>Distinct from {@link #ollama} only in identity and credentials, but both matter. The id
     * appearing in the event log and in {@code jclaw models} should say what the operator actually
     * configured, not "ollama" because that adapter happened to be reusable. And unlike Ollama,
     * some of these servers do check a bearer token — vLLM started with {@code --api-key}, or an
     * LM Studio instance behind a proxy — so a key is sent when provided.
     *
     * <p>The key is <em>optional</em> by construction: no credential hint is registered, so a
     * missing key means requests go out unauthenticated (correct for most local servers) rather
     * than failing with AUTH before reaching the network. A server that does require one answers
     * 401, which classifies as an auth failure with the server's own message attached.
     */
    public static OpenAiCompatibleModelProvider local(String baseUrl, String apiKey) {
        return new OpenAiCompatibleModelProvider(
                "local",
                baseUrl,
                Optional.ofNullable(emptyToNull(apiKey)),
                Optional.empty(),
                Map.of(),
                notAnthropicShorthand(),
                defaultClient());
    }

    /**
     * OpenRouter: a gateway that fronts many providers behind one OpenAI-compatible endpoint.
     *
     * <p>Two things differ from plain OpenAI and both matter.
     *
     * <p><b>Model ids are namespaced</b> as {@code org/model} — {@code anthropic/claude-sonnet-4.6},
     * {@code openai/gpt-5.2}. The model predicate requires that shape, so a failover chain never
     * routes a bare id here only to get an unhelpful 404 from a gateway that cannot resolve it.
     *
     * <p><b>Attribution headers are optional but recommended.</b> {@code HTTP-Referer} and
     * {@code X-Title} identify the calling app on OpenRouter's dashboards. They are sent only when
     * configured — an empty header would be worse than none.
     *
     * @param referer value for {@code HTTP-Referer}; omitted when blank
     * @param title   value for {@code X-Title}; omitted when blank
     */
    public static OpenAiCompatibleModelProvider openrouter(
            String apiKey, String baseUrl, String referer, String title) {

        Map<String, String> headers = new LinkedHashMap<>();
        if (referer != null && !referer.isBlank()) {
            headers.put("HTTP-Referer", referer);
        }
        if (title != null && !title.isBlank()) {
            headers.put("X-Title", title);
        }
        return new OpenAiCompatibleModelProvider(
                "openrouter",
                baseUrl == null || baseUrl.isBlank() ? OPENROUTER_BASE_URL : baseUrl,
                Optional.ofNullable(emptyToNull(apiKey)),
                Optional.of("OPENROUTER_API_KEY"),
                headers,
                OpenAiCompatibleModelProvider::isNamespacedModelId,
                defaultClient());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Whether {@code model} carries the {@code org/model} prefix OpenRouter requires.
     *
     * <p>Rejecting an un-namespaced id here is the difference between a clear
     * "no provider available for model claude-opus-5" and a 404 from a gateway the user did not
     * realise they were talking to.
     */
    static boolean isNamespacedModelId(String model) {
        int slash = model.indexOf('/');
        return slash > 0 && slash < model.length() - 1;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean supports(String model) {
        Objects.requireNonNull(model, "model");
        return supportsModel.test(model);
    }

    @Override
    public Result<ModelResponse, ProviderFailure> complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");

        if (apiKey.isEmpty() && credentialHint.isPresent()) {
            // Reported here rather than thrown at construction, so a missing key is an actionable
            // turn failure instead of a context that refuses to start.
            log.debug("{}: no credential ({} unset) -> AUTH failure", id, credentialHint.get());
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.AUTH, credentialHint.get() + " is not set"));
        }

        // Capability ids contain dots; tool names may not. Build the mapping once so the
        // response can be decoded by lookup rather than by parsing an ambiguous encoding.
        Map<String, String> toolNames;
        try {
            toolNames = ToolNames.wireNamesFor(
                    request.tools().stream().map(ToolSpec::name).toList());
        } catch (IllegalArgumentException e) {
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, e.getMessage()));
        }

        String body;
        try {
            body = mapper.writeValueAsString(toWireRequest(request));
        } catch (RuntimeException e) {
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, "could not encode request"));
        }

        log.debug("{}: POST {} model {} ({} messages, {} tools, {} header(s))",
                id, endpoint, request.model(), request.messages().size(),
                request.tools().size(), extraHeaders.size());
        if (log.isTraceEnabled()) {
            // The body's message content already passed the kernel's redaction on its way into
            // the transcript; the Authorization header is never logged.
            log.trace("{}: request body ({} bytes): {}", id, body.length(), boundForTrace(body));
        }

        HttpRequest.Builder http = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        apiKey.ifPresent(key -> http.header("Authorization", "Bearer " + key));
        extraHeaders.forEach(http::header);

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
            ProviderFailure failure = classify(status, response.body());
            log.debug("{}: classified as {} (retryable={})", id, failure.kind(), failure.retryable());
            return Result.err(failure);
        }
        try {
            ModelResponse decoded = fromWireResponse(response.body(), request.model(), toolNames);
            log.debug("{}: decoded reply from {} (stop {}, in {} out {} tokens, {} tool use(s))",
                    id, decoded.modelId(), decoded.stopReason(), decoded.usage().inputTokens(),
                    decoded.usage().outputTokens(), decoded.toolUses().size());
            log.trace("{}: response body: {}", id, boundForTrace(response.body()));
            return Result.ok(decoded);
        } catch (RuntimeException e) {
            log.debug("{}: unparseable response ({})", id, e.getClass().getSimpleName());
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.UPSTREAM, "unparseable response"));
        }
    }

    /**
     * Maps an HTTP status to a failure category.
     *
     * <p>For a rejected request the provider's own {@code error.message} is included. It describes
     * the shape of the request <em>we</em> sent — a bad tool name, an unsupported parameter — and
     * is by far the most useful thing available at that moment. Without it, "request rejected" is
     * all a user ever sees, and the actual cause takes a packet capture to find.
     *
     * <p>Only extracted for 4xx invalid-request responses, bounded, and redacted downstream before
     * it reaches the event log.
     */
    private ProviderFailure classify(int status, String body) {
        return switch (status) {
            case 401, 403 -> ProviderFailure.of(ProviderFailure.Kind.AUTH, "credentials rejected");
            case 404 -> ProviderFailure.of(
                    ProviderFailure.Kind.UNKNOWN_MODEL, "model or endpoint not found");
            case 429 -> ProviderFailure.of(ProviderFailure.Kind.RATE_LIMIT, "rate limited");
            case 400, 422 -> ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, errorMessage(body));
            default -> ProviderFailure.of(ProviderFailure.Kind.UPSTREAM, "http " + status);
        };
    }

    /** Pulls {@code error.message} out of a provider error body, falling back to a bare category. */
    @SuppressWarnings("unchecked")
    private String errorMessage(String body) {
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

    // --- wire encoding ---

    private Map<String, Object> toWireRequest(ModelRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!request.system().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.system()));
        }
        for (ChatMessage message : request.messages()) {
            messages.addAll(toWireMessages(message));
        }

        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("model", request.model());
        wire.put("messages", messages);
        wire.put("max_tokens", request.maxTokens());
        request.temperature().ifPresent(temperature -> wire.put("temperature", temperature));
        if (!request.tools().isEmpty()) {
            wire.put("tools", request.tools().stream().map(this::toWireTool).toList());
        }
        return wire;
    }

    private Map<String, Object> toWireTool(ToolSpec spec) {
        Map<String, Object> function = new LinkedHashMap<>();
        // builtin.read_file -> builtin_read_file
        function.put("name", ToolNames.toWire(spec.name()));
        function.put("description", spec.description());
        function.put("parameters", spec.inputSchema());
        return Map.of("type", "function", "function", function);
    }

    /**
     * Converts one message.
     *
     * <p>Returns a list because a single tool-result message in jclaw's vocabulary becomes several
     * OpenAI messages — the format uses one message per tool result, each keyed by call id.
     */
    private List<Map<String, Object>> toWireMessages(ChatMessage message) {
        if (message.role() == ChatMessage.Role.TOOL) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.ToolResult result) {
                    results.add(Map.of(
                            "role", "tool",
                            "tool_call_id", result.callId(),
                            "content", result.content()));
                }
            }
            return results;
        }

        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", switch (message.role()) {
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case USER, TOOL -> "user";
        });
        wire.put("content", message.displayText());

        List<Map<String, Object>> toolCalls = message.toolUses().stream()
                .map(use -> Map.<String, Object>of(
                        "id", use.callId(),
                        "type", "function",
                        "function", Map.of(
                                // Replayed history must use the same encoding as the request that
                                // produced it, or the follow-up turn is rejected.
                                "name", ToolNames.toWire(use.name()),
                                "arguments", mapper.writeValueAsString(use.input()))))
                .toList();
        if (!toolCalls.isEmpty()) {
            wire.put("tool_calls", toolCalls);
        }
        return List.of(wire);
    }

    @SuppressWarnings("unchecked")
    private ModelResponse fromWireResponse(
            String body, String requestedModel, Map<String, String> toolNames) {
        Map<String, Object> parsed = mapper.readValue(body, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("no choices in response");
        }
        Map<String, Object> choice = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");

        List<ContentBlock> blocks = new ArrayList<>();
        Object content = message.get("content");
        if (content instanceof String text && !text.isBlank()) {
            blocks.add(new ContentBlock.Text(text));
        }
        if (message.get("tool_calls") instanceof List<?> rawCalls) {
            for (Object rawCall : rawCalls) {
                if (rawCall instanceof Map<?, ?> call) {
                    blocks.add(toToolUse((Map<String, Object>) call, toolNames));
                }
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(new ContentBlock.Text(""));
        }

        return new ModelResponse(
                new ChatMessage(ChatMessage.Role.ASSISTANT, blocks),
                toStopReason(String.valueOf(choice.get("finish_reason"))),
                toUsage((Map<String, Object>) parsed.get("usage")),
                String.valueOf(parsed.getOrDefault("model", requestedModel)));
    }

    @SuppressWarnings("unchecked")
    private ContentBlock.ToolUse toToolUse(Map<String, Object> call, Map<String, String> toolNames) {
        Map<String, Object> function = (Map<String, Object>) call.get("function");
        // Arguments arrive as a JSON *string*, not an object. Parsing rather than string-matching
        // matters: escaping differs between servers and models.
        Map<String, Object> arguments = Map.of();
        Object raw = function.get("arguments");
        if (raw instanceof String json && !json.isBlank()) {
            try {
                arguments = mapper.readValue(json, Map.class);
            } catch (RuntimeException e) {
                arguments = Map.of();
            }
        }
        // Decode by lookup. An unknown name passes through unchanged so the kernel can deny it
        // as an unknown capability rather than it being silently rewritten.
        String wireName = String.valueOf(function.get("name"));
        return new ContentBlock.ToolUse(
                String.valueOf(call.get("id")), ToolNames.fromWire(wireName, toolNames), arguments);
    }

    private static StopReason toStopReason(String finishReason) {
        return switch (finishReason) {
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "length" -> StopReason.MAX_TOKENS;
            case "content_filter" -> StopReason.REFUSAL;
            default -> StopReason.END_TURN;
        };
    }

    private static Usage toUsage(Map<String, Object> usage) {
        if (usage == null) {
            // Local servers frequently omit usage. Zero is honest; inventing numbers would corrupt
            // budget accounting in a way that is hard to notice.
            return Usage.ZERO;
        }
        return Usage.of(asLong(usage.get("prompt_tokens")), asLong(usage.get("completion_tokens")));
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** Bounds wire payloads for TRACE lines and folds newlines so one log line stays one line. */
    private static String boundForTrace(String text) {
        String bounded = text.length() > 4000 ? text.substring(0, 4000) + "…" : text;
        return bounded.replace("\n", "\\n");
    }

}
