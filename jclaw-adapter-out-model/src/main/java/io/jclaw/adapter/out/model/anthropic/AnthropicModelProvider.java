// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.model.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.jclaw.ports.Result;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ModelExchange.ModelRequest;
import io.jclaw.ports.model.ModelExchange.ModelResponse;
import io.jclaw.ports.model.ModelExchange.StopReason;
import io.jclaw.ports.model.ModelExchange.ToolSpec;
import io.jclaw.ports.model.ModelExchange.Usage;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.model.ToolNames;
import io.jclaw.ports.observability.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * {@link ModelProvider} backed by the official Anthropic Java SDK.
 *
 * <p>The SDK is deliberately confined to this class. Nothing else in jclaw imports
 * {@code com.anthropic.*} — the rest of the harness speaks only its own
 * {@link io.jclaw.ports.model} vocabulary. That is the entire point of the port: a second
 * provider, or a future SDK major version, changes this file and nothing else.
 *
 * <p>Using the SDK rather than hand-rolling the Messages API buys correct auth resolution
 * (env var, then OAuth profile), retry and backoff, and the current request shape — none of which
 * is worth re-deriving from memory and getting subtly wrong.
 *
 * <p>Failures are translated into {@link ProviderFailure} categories here. SDK exception messages
 * can carry request context, so they are classified by exception type and discarded rather than
 * propagated — the sanitizing happens at this boundary so nothing above has to remember to do it.
 */
public final class AnthropicModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicModelProvider.class);

    /**
     * Default model. Claude Opus 5 is the current flagship; thinking is on by default on this
     * model, so the loop gets reasoning without configuring anything.
     */
    public static final String DEFAULT_MODEL = "claude-opus-5";

    private final AnthropicClient client;
    private final boolean adaptiveThinking;

    /** Builds a client from the ambient environment: {@code ANTHROPIC_API_KEY} or an OAuth profile. */
    public AnthropicModelProvider() {
        this(AnthropicOkHttpClient.fromEnv(), true);
    }

    public AnthropicModelProvider(AnthropicClient client, boolean adaptiveThinking) {
        this.client = Objects.requireNonNull(client, "client");
        this.adaptiveThinking = adaptiveThinking;
    }

    /** Builds a provider from an explicit API key. */
    public static AnthropicModelProvider withApiKey(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey");
        return new AnthropicModelProvider(
                AnthropicOkHttpClient.builder().apiKey(apiKey).build(), true);
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public boolean supports(String model) {
        Objects.requireNonNull(model, "model");
        return model.startsWith("claude-");
    }

    @Override
    public Result<ModelResponse, ProviderFailure> complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");

        // Capability ids contain dots; Anthropic tool names may not. Same encoding as the
        // OpenAI-compatible adapter, and the same lookup-based decode.
        Map<String, String> toolNames;
        try {
            toolNames = ToolNames.wireNamesFor(
                    request.tools().stream().map(ToolSpec::name).toList());
        } catch (IllegalArgumentException e) {
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, e.getMessage()));
        }

        log.debug("anthropic: create message, model {} ({} messages, {} tools, maxTokens {}, "
                        + "adaptive thinking {})",
                request.model(), request.messages().size(), request.tools().size(),
                request.maxTokens(), adaptiveThinking);
        try {
            Message response = client.messages().create(toParams(request));
            log.debug("anthropic: response from {} (stop {}, in {} out {} tokens)",
                    response.model(), response.stopReason().map(Object::toString).orElse("-"),
                    response.usage().inputTokens(), response.usage().outputTokens());
            return Result.ok(fromMessage(response, toolNames));
        } catch (RateLimitException e) {
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.RATE_LIMIT, "rate limited"));
        } catch (UnauthorizedException | PermissionDeniedException e) {
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.AUTH, "credentials rejected"));
        } catch (NotFoundException e) {
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.UNKNOWN_MODEL, request.model()));
        } catch (BadRequestException e) {
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.INVALID_REQUEST, "request rejected"));
        } catch (AnthropicServiceException e) {
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.UPSTREAM, "upstream error"));
        } catch (RuntimeException e) {
            // Anything the SDK did not classify. The exception *message* can carry a URL or a
            // request body, so it is dropped — but the exception *class name* cannot contain user
            // data and is the single most useful diagnostic here. Reporting a bare "transport
            // failure" for every RuntimeException hides real defects: a missing GraalVM reflection
            // registration under native image looks exactly like a network outage without it.
            log.debug("anthropic: unclassified {} -> {}", e.getClass().getSimpleName(),
                    classifyUnexpected(e).kind());
            return Result.err(classifyUnexpected(e));
        }
    }

    /**
     * Classifies an exception the SDK did not translate.
     *
     * <p>Genuine connectivity faults are {@link ProviderFailure.Kind#TRANSPORT} and worth
     * retrying; everything else is an unexpected internal condition that retrying will not fix,
     * and is reported with its exception class so the cause is identifiable from a log line.
     */
    private static ProviderFailure classifyUnexpected(RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        // Jackson 2 exceptions extend IOException, so a naive IOException check reports a
        // serialization defect as a network outage — which is exactly how a missing GraalVM
        // reflection registration disguised itself as connectivity during native testing.
        boolean serialization = root.getClass().getName().startsWith("com.fasterxml.jackson")
                || root.getClass().getName().startsWith("tools.jackson");
        boolean connectivity = !serialization
                && (root instanceof IOException
                || root instanceof SocketException
                || root instanceof UnknownHostException
                || root instanceof TimeoutException);

        String type = root.getClass().getSimpleName();
        return connectivity
                ? ProviderFailure.of(ProviderFailure.Kind.TRANSPORT, "transport failure: " + type)
                : ProviderFailure.of(ProviderFailure.Kind.UPSTREAM, "unexpected " + type);
    }

    /**
     * Streams a reply, emitting text as it arrives and returning the assembled response.
     *
     * <p>Uses the SDK's {@link MessageAccumulator} rather than reassembling events by hand. The
     * event stream carries partial JSON for tool-call arguments and incremental usage updates;
     * rebuilding a correct {@code Message} from those is exactly the fiddly work the SDK already
     * does, and getting it subtly wrong would corrupt tool calls rather than fail loudly.
     *
     * <p>The sink sees only prose deltas. Partial tool-call arguments are deliberately withheld:
     * they are unparseable mid-stream, and showing half a JSON object to a user is noise.
     */
    @Override
    public Result<ModelResponse, ProviderFailure> stream(
            ModelRequest request, Consumer<StreamEvent> sink) {

        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");

        Map<String, String> toolNames;
        try {
            toolNames = ToolNames.wireNamesFor(
                    request.tools().stream().map(ToolSpec::name).toList());
        } catch (IllegalArgumentException e) {
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.INVALID_REQUEST, e.getMessage()));
        }

        log.debug("anthropic: create streaming message, model {} ({} messages, {} tools, "
                        + "maxTokens {})",
                request.model(), request.messages().size(), request.tools().size(),
                request.maxTokens());
        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> events =
                     client.messages().createStreaming(toParams(request))) {

            events.stream().forEach(event -> {
                accumulator.accumulate(event);
                event.contentBlockDelta()
                        .flatMap(delta -> delta.delta().text())
                        .ifPresent(text -> sink.accept(new StreamEvent.TextDelta(text.text())));
            });

            ModelResponse response = fromMessage(accumulator.message(), toolNames);
            log.debug("anthropic: stream complete from {} (stop {}, in {} out {} tokens)",
                    response.modelId(), response.stopReason(),
                    response.usage().inputTokens(), response.usage().outputTokens());
            sink.accept(new StreamEvent.Completed(response));
            return Result.ok(response);

        } catch (RateLimitException e) {
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.RATE_LIMIT, "rate limited"));
        } catch (UnauthorizedException | PermissionDeniedException e) {
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.AUTH, "credentials rejected"));
        } catch (AnthropicServiceException e) {
            return Result.err(ProviderFailure.of(ProviderFailure.Kind.UPSTREAM, "upstream error"));
        } catch (RuntimeException e) {
            return Result.err(classifyUnexpected(e));
        }
    }

    // --- Translation: jclaw vocabulary -> SDK ---

    private MessageCreateParams toParams(ModelRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens());

        // W3C trace context, when the interpreter opened a scope for this call. Two opaque
        // identifiers and nothing else, so a gateway or proxy in front of the API can join its
        // trace of the request to jclaw's trace of the run.
        TraceContext.current().ifPresent(trace ->
                builder.putAdditionalHeader("traceparent", trace.traceparent()));

        if (!request.system().isBlank()) {
            builder.system(request.system());
        }
        if (adaptiveThinking) {
            // Adaptive thinking lets the model decide how much to reason per turn. The fixed
            // budget_tokens form is removed on current models and returns 400.
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        }
        for (ToolSpec tool : request.tools()) {
            builder.addTool(toSdkTool(tool));
        }
        for (ChatMessage message : request.messages()) {
            toMessageParam(message).ifPresent(builder::addMessage);
        }
        return builder.build();
    }

    private static Tool toSdkTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();

        Object rawProperties = spec.inputSchema().get("properties");
        if (rawProperties instanceof Map<?, ?> map) {
            map.forEach((key, value) ->
                    properties.putAdditionalProperty(String.valueOf(key), JsonValue.from(value)));
        }

        Tool.InputSchema.Builder schema = Tool.InputSchema.builder().properties(properties.build());
        if (spec.inputSchema().get("required") instanceof List<?> required) {
            schema.required(required.stream().map(String::valueOf).toList());
        }
        return Tool.builder()
                // builtin.read_file -> builtin_read_file
                .name(ToolNames.toWire(spec.name()))
                .description(spec.description())
                .inputSchema(schema.build())
                .build();
    }

    /**
     * Converts one jclaw message to an SDK message.
     *
     * <p>Returns empty for a message that produced no blocks — the API rejects empty content, and
     * a system message here would be misplaced (it travels in the top-level {@code system} field).
     */
    // The SDK's ToolUseBlockParam.Builder.input takes a raw type, so passing a JsonValue built
    // from Map<String, Object> is an unchecked call. Nothing here is cast; the warning is the
    // vendor's signature, not a hole in this conversion.
    @SuppressWarnings("unchecked")
    private static Optional<MessageParam> toMessageParam(ChatMessage message) {
        if (message.role() == ChatMessage.Role.SYSTEM) {
            return Optional.empty();
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (io.jclaw.ports.model.ContentBlock block : message.content()) {
            switch (block) {
                case io.jclaw.ports.model.ContentBlock.Text text -> {
                    if (!text.text().isEmpty()) {
                        blocks.add(ContentBlockParam.ofText(
                                TextBlockParam.builder().text(text.text()).build()));
                    }
                }
                case io.jclaw.ports.model.ContentBlock.ToolUse use -> blocks.add(
                        ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                                .id(use.callId())
                                // Replayed history must match the encoding of the request that
                                // produced it, or the follow-up turn is rejected.
                                .name(ToolNames.toWire(use.name()))
                                .input(JsonValue.from(use.input()))
                                .build()));
                case io.jclaw.ports.model.ContentBlock.ToolResult result -> blocks.add(
                        ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                                .toolUseId(result.callId())
                                .content(result.content())
                                .isError(result.isError())
                                .build()));
                case io.jclaw.ports.model.ContentBlock.Image image -> blocks.add(
                        ContentBlockParam.ofImage(ImageBlockParam.builder()
                                .source(Base64ImageSource.builder()
                                        .mediaType(Base64ImageSource.MediaType.of(
                                                image.mediaType()))
                                        .data(image.data())
                                        .build())
                                .build()));
                case io.jclaw.ports.model.ContentBlock.Thinking ignored -> {
                    // Reasoning is not replayed across providers; the SDK re-derives it.
                }
            }
        }
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        // Tool results travel in a user-role message, which is what the API expects.
        MessageParam.Role role = switch (message.role()) {
            case ASSISTANT -> MessageParam.Role.ASSISTANT;
            case USER, TOOL, SYSTEM -> MessageParam.Role.USER;
        };
        return Optional.of(MessageParam.builder()
                .role(role)
                .contentOfBlockParams(blocks)
                .build());
    }

    // --- Translation: SDK -> jclaw vocabulary ---

    private static ModelResponse fromMessage(Message message, Map<String, String> toolNames) {
        List<io.jclaw.ports.model.ContentBlock> blocks = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(text ->
                    blocks.add(new io.jclaw.ports.model.ContentBlock.Text(text.text())));
            block.toolUse().ifPresent(use ->
                    blocks.add(new io.jclaw.ports.model.ContentBlock.ToolUse(
                            use.id(),
                            // Decode by lookup; an unknown name passes through so the kernel can
                            // deny it rather than have it silently rewritten.
                            ToolNames.fromWire(use.name(), toolNames),
                            toArgumentMap(use._input()))));
        }
        if (blocks.isEmpty()) {
            // A reply that is entirely reasoning still needs a block, or the message is invalid.
            blocks.add(new io.jclaw.ports.model.ContentBlock.Text(""));
        }

        return new ModelResponse(
                new ChatMessage(ChatMessage.Role.ASSISTANT, blocks),
                toStopReason(message),
                toUsage(message),
                message.model().toString());
    }

    /**
     * Converts a tool-call input to a plain map.
     *
     * <p>Tool input is model-generated JSON and must be parsed, never string-matched — escaping
     * differs across models.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toArgumentMap(JsonValue input) {
        Object converted = input.convert(Object.class);
        if (converted instanceof Map<?, ?> map) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            map.forEach((key, value) -> arguments.put(String.valueOf(key), value));
            return arguments;
        }
        return Map.of();
    }

    private static StopReason toStopReason(Message message) {
        return message.stopReason()
                .map(reason -> {
                    String token = reason.toString();
                    return switch (token) {
                        case "tool_use" -> StopReason.TOOL_USE;
                        case "max_tokens" -> StopReason.MAX_TOKENS;
                        case "stop_sequence" -> StopReason.STOP_SEQUENCE;
                        case "refusal" -> StopReason.REFUSAL;
                        default -> StopReason.END_TURN;
                    };
                })
                .orElse(StopReason.END_TURN);
    }

    private static Usage toUsage(Message message) {
        var usage = message.usage();
        return new Usage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L),
                usage.cacheCreationInputTokens().orElse(0L));
    }
}
