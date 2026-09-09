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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Provider for any server speaking the OpenAI chat-completions API.
 *
 * <p>One adapter covers OpenAI itself, Ollama, vLLM, LM Studio, and most local inference servers,
 * because they all converged on the same wire format. Written against raw HTTP rather than an SDK:
 * there is no single official client for "OpenAI-compatible", and the surface used here is one
 * endpoint, in two modes (buffered and server-sent events).
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

    // --- request preparation, shared by both modes ---

    /** A request ready to send: the wire body and the tool-name mapping needed to decode replies. */
    private record Prepared(Map<String, String> toolNames, HttpRequest http) {
    }

    /**
     * Validates credentials and tool names, then encodes the request.
     *
     * <p>Everything that can fail <em>before</em> the network does so here, identically for the
     * buffered and streaming paths, so a missing key or an ambiguous tool name produces the same
     * failure whichever way the loop asked.
     */
    private Result<Prepared, ProviderFailure> prepare(ModelRequest request, boolean streaming) {
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
            Map<String, Object> wire = toWireRequest(request);
            if (streaming) {
                wire.put("stream", true);
                // Ask for the usage chunk at the end of the stream. OpenAI, OpenRouter, vLLM and
                // Ollama honour it; servers that do not simply omit usage, and accounting falls
                // back to zero rather than guessing.
                wire.put("stream_options", Map.of("include_usage", true));
            }
            body = mapper.writeValueAsString(wire);
        } catch (RuntimeException e) {
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, "could not encode request"));
        }

        log.debug("{}: POST {} model {} ({} messages, {} tools, {} header(s), streaming {})",
                id, endpoint, request.model(), request.messages().size(),
                request.tools().size(), extraHeaders.size(), streaming);
        if (log.isTraceEnabled()) {
            // The body's message content already passed the kernel's redaction on its way into
            // the transcript; the Authorization header is never logged.
            log.trace("{}: request body ({} bytes): {}", id, body.length(), boundForTrace(body));
        }

        HttpRequest.Builder http = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (streaming) {
            http.header("Accept", "text/event-stream");
        }
        apiKey.ifPresent(key -> http.header("Authorization", "Bearer " + key));
        extraHeaders.forEach(http::header);
        return Result.ok(new Prepared(toolNames, http.build()));
    }

    // --- buffered mode ---

    @Override
    public Result<ModelResponse, ProviderFailure> complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");

        Result<Prepared, ProviderFailure> prepared = prepare(request, false);
        if (prepared instanceof Result.Err<Prepared, ProviderFailure> err) {
            return Result.err(err.error());
        }
        Prepared ready = ((Result.Ok<Prepared, ProviderFailure>) prepared).value();

        HttpResponse<String> response;
        try {
            response = client.send(ready.http(), HttpResponse.BodyHandlers.ofString());
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
            ProviderFailure failure = OpenAiCompatibleFailures.classify(status, response.body(), mapper);
            log.debug("{}: classified as {} (retryable={})", id, failure.kind(), failure.retryable());
            return Result.err(failure);
        }
        try {
            ModelResponse decoded = fromWireResponse(response.body(), request.model(), ready.toolNames());
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

    // --- streaming mode ---

    /**
     * Streams over server-sent events.
     *
     * <p>Same request as {@link #complete} plus {@code stream: true}; the reply arrives as
     * {@code data:} lines, each a chunk with a {@code delta} of prose and/or partial tool calls,
     * ending with {@code data: [DONE]}. Prose deltas reach the sink as they arrive. Tool-call
     * arguments do not: they stream as fragments of JSON, unparseable until the last one lands,
     * so the sink is told a call <em>started</em> and gets the complete call in the final
     * response, which is the same {@link ModelResponse} the buffered path would have produced.
     *
     * <p>A failure once bytes have started flowing is reported like any other transport failure.
     * The failover chain knows not to retry a stream that already showed the user text.
     */
    @Override
    public Result<ModelResponse, ProviderFailure> stream(
            ModelRequest request, Consumer<StreamEvent> sink) {

        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");

        Result<Prepared, ProviderFailure> prepared = prepare(request, true);
        if (prepared instanceof Result.Err<Prepared, ProviderFailure> err) {
            return Result.err(err.error());
        }
        Prepared ready = ((Result.Ok<Prepared, ProviderFailure>) prepared).value();

        HttpResponse<InputStream> response;
        try {
            response = client.send(ready.http(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.debug("{}: transport failure ({})", id, e.getClass().getSimpleName());
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.TRANSPORT, "transport failure"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.TRANSPORT, "interrupted"));
        }

        int status = response.statusCode();
        log.debug("{}: HTTP {} (event stream)", id, status);
        try (InputStream body = response.body()) {
            if (status >= 400) {
                String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                ProviderFailure failure = OpenAiCompatibleFailures.classify(status, text, mapper);
                log.debug("{}: classified as {} (retryable={})", id, failure.kind(), failure.retryable());
                return Result.err(failure);
            }

            StreamAssembler assembler = new StreamAssembler(request.model(), ready.toolNames(), sink);
            readEvents(body, assembler);
            ModelResponse assembled = assembler.finish();
            log.debug("{}: stream complete from {} (stop {}, in {} out {} tokens, {} tool use(s), "
                            + "{} chunk(s))",
                    id, assembled.modelId(), assembled.stopReason(), assembled.usage().inputTokens(),
                    assembled.usage().outputTokens(), assembled.toolUses().size(), assembler.chunks);
            sink.accept(new StreamEvent.Completed(assembled));
            return Result.ok(assembled);
        } catch (IOException e) {
            log.debug("{}: stream broke ({})", id, e.getClass().getSimpleName());
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.TRANSPORT, "stream interrupted"));
        } catch (RuntimeException e) {
            log.debug("{}: unparseable stream ({})", id, e.getClass().getSimpleName());
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.UPSTREAM, "unparseable stream"));
        }
    }

    /**
     * Reads server-sent events until {@code [DONE]} or end of stream.
     *
     * <p>Follows the SSE framing rather than assuming one line per event: an event's data may
     * span several {@code data:} lines, is terminated by a blank line, and {@code event:},
     * {@code id:}, and comment lines are ignored.
     */
    @SuppressWarnings("unchecked")
    private void readEvents(InputStream body, StreamAssembler assembler) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (!dispatch(data, assembler)) {
                    return;
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).stripLeading());
            }
        }
        dispatch(data, assembler);
    }

    /** Feeds one buffered event to the assembler. Returns false on the terminal sentinel. */
    @SuppressWarnings("unchecked")
    private boolean dispatch(StringBuilder data, StreamAssembler assembler) {
        if (data.isEmpty()) {
            return true;
        }
        String event = data.toString();
        data.setLength(0);
        if ("[DONE]".equals(event.strip())) {
            return false;
        }
        assembler.accept(mapper.readValue(event, Map.class));
        return true;
    }

    /**
     * Rebuilds a complete response from streamed chunks.
     *
     * <p>Tool calls are keyed by the chunk's {@code index}: OpenAI's format sends a call's id and
     * name in its first fragment and appends argument text in the rest, and several calls may
     * interleave. Fragments are concatenated per index and parsed once, at the end.
     */
    private final class StreamAssembler {

        private final String requestedModel;
        private final Map<String, String> toolNames;
        private final Consumer<StreamEvent> sink;

        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, PartialToolCall> toolCalls = new TreeMap<>();
        private String modelId;
        private String finishReason;
        private Usage usage = Usage.ZERO;
        private int chunks;

        private StreamAssembler(String requestedModel, Map<String, String> toolNames, Consumer<StreamEvent> sink) {
            this.requestedModel = requestedModel;
            this.toolNames = toolNames;
            this.sink = sink;
        }

        @SuppressWarnings("unchecked")
        void accept(Map<String, Object> chunk) {
            chunks++;
            if (chunk.get("model") instanceof String model && !model.isBlank()) {
                modelId = model;
            }
            if (chunk.get("usage") instanceof Map<?, ?> reported) {
                usage = toUsage((Map<String, Object>) reported);
            }
            if (!(chunk.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
                return; // the usage-only trailer, or a keepalive
            }
            Map<String, Object> choice = (Map<String, Object>) choices.get(0);
            if (choice.get("finish_reason") instanceof String reason) {
                finishReason = reason;
            }
            if (!(choice.get("delta") instanceof Map<?, ?> delta)) {
                return;
            }
            if (delta.get("content") instanceof String piece && !piece.isEmpty()) {
                text.append(piece);
                sink.accept(new StreamEvent.TextDelta(piece));
            }
            if (delta.get("tool_calls") instanceof List<?> fragments) {
                for (Object fragment : fragments) {
                    if (fragment instanceof Map<?, ?> call) {
                        acceptToolFragment((Map<String, Object>) call);
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void acceptToolFragment(Map<String, Object> fragment) {
            int index = fragment.get("index") instanceof Number n ? n.intValue() : 0;
            PartialToolCall call = toolCalls.computeIfAbsent(index, ignored -> new PartialToolCall());
            if (fragment.get("id") instanceof String callId && !callId.isBlank()) {
                call.id = callId;
            }
            if (fragment.get("function") instanceof Map<?, ?> function) {
                Map<String, Object> fn = (Map<String, Object>) function;
                if (fn.get("name") instanceof String name && !name.isBlank()) {
                    call.name = name;
                }
                if (fn.get("arguments") instanceof String piece) {
                    call.arguments.append(piece);
                }
            }
            if (!call.announced && call.id != null && call.name != null) {
                call.announced = true;
                sink.accept(new StreamEvent.ToolUseStarted(
                        call.id, ToolNames.fromWire(call.name, toolNames)));
            }
        }

        ModelResponse finish() {
            List<ContentBlock> blocks = new ArrayList<>();
            if (!text.isEmpty()) {
                blocks.add(new ContentBlock.Text(text.toString()));
            }
            for (Map.Entry<Integer, PartialToolCall> entry : toolCalls.entrySet()) {
                PartialToolCall call = entry.getValue();
                if (call.name == null) {
                    continue; // a fragment with no name is not a call
                }
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name);
                function.put("arguments", call.arguments.toString());
                Map<String, Object> wire = new LinkedHashMap<>();
                wire.put("id", call.id != null ? call.id : "call_" + entry.getKey());
                wire.put("function", function);
                blocks.add(toToolUse(wire, toolNames));
            }
            if (blocks.isEmpty()) {
                blocks.add(new ContentBlock.Text(""));
            }
            StopReason stop = finishReason != null
                    ? toStopReason(finishReason)
                    : (toolCalls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE);
            return new ModelResponse(
                    new ChatMessage(ChatMessage.Role.ASSISTANT, blocks),
                    stop,
                    usage,
                    modelId != null ? modelId : requestedModel);
        }

        private final class PartialToolCall {
            String id;
            String name;
            final StringBuilder arguments = new StringBuilder();
            boolean announced;
        }
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
