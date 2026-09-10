// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.mcp;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The reverse direction, bounded: turning an MCP server's {@code sampling/createMessage} into a
 * model request the host is willing to make.
 *
 * <p>Sampling inverts who is asking. Everywhere else jclaw calls a server; here a server calls
 * jclaw, and asks it to spend money on a model. The request is written by third-party code, so
 * every part of it is input to be bounded rather than a setting to be honoured:
 *
 * <ul>
 *   <li><strong>No tools.</strong> A sampled call publishes nothing. A server that could put
 *       tools in front of the model would have found a way to cause effects without going
 *       through the capability host, which is the one boundary that must not have a side door.</li>
 *   <li><strong>The host's model.</strong> The server may express a preference and it is
 *       ignored. Letting a server pick the model is letting it pick the price.</li>
 *   <li><strong>Bounded output, bounded input.</strong> {@code maxTokens} is clamped and the
 *       message list is capped, because "how much may this cost" is not the caller's to
 *       decide.</li>
 *   <li><strong>Text only.</strong> Images and other blocks are dropped rather than forwarded.
 *       An image is expensive and a server has no way to have obtained one the user consented
 *       to send.</li>
 * </ul>
 *
 * <p>Pure: it takes the decoded params and the host's limits and returns a request or a reason.
 * Whether sampling is allowed at all, and how many times, is the caller's decision.
 */
public final class SamplingPolicy {

    /** How many messages of a server's conversation are forwarded. */
    public static final int MAX_MESSAGES = 40;

    /** Ceiling on a sampled call's output, whatever the server asked for. */
    public static final int MAX_OUTPUT_TOKENS = 4_096;

    /** Characters of any one message that are forwarded. */
    public static final int MAX_MESSAGE_CHARS = 16_384;

    private SamplingPolicy() {
    }

    /**
     * Builds the request to make, or says why not.
     *
     * @param params      the server's {@code sampling/createMessage} params, decoded
     * @param model       the host's model; the server's preference is not consulted
     * @param maxTokens   the host's own output cap, clamped to {@link #MAX_OUTPUT_TOKENS}
     */
    @SuppressWarnings("unchecked")
    public static Result<ModelRequest, String> requestFor(
            Map<String, Object> params, String model, int maxTokens) {
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(model, "model");

        if (!(params.get("messages") instanceof List<?> raw) || raw.isEmpty()) {
            return Result.err("sampling_requires_messages");
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (Object element : raw) {
            if (messages.size() >= MAX_MESSAGES) {
                break;
            }
            if (!(element instanceof Map<?, ?> message)) {
                continue;
            }
            Optional<ChatMessage> converted = convert((Map<String, Object>) message);
            converted.ifPresent(messages::add);
        }
        if (messages.isEmpty()) {
            return Result.err("sampling_messages_unusable");
        }
        // A provider rejects a conversation that does not open with a user turn, and a server
        // that sends one starting with an assistant message is asking for a 400, not a sample.
        if (messages.get(0).role() != ChatMessage.Role.USER) {
            return Result.err("sampling_must_start_with_a_user_message");
        }

        String system = params.get("systemPrompt") instanceof String prompt
                ? bound(prompt, MAX_MESSAGE_CHARS) : "";
        int requested = params.get("maxTokens") instanceof Number number ? number.intValue() : maxTokens;
        int output = Math.clamp(requested, 1, Math.min(maxTokens, MAX_OUTPUT_TOKENS));

        // No tools, deliberately: see the class note.
        return Result.ok(new ModelRequest(model, system, messages, List.of(), output, Optional.empty()));
    }

    /** The result envelope a server expects back, given the model's text. */
    public static Map<String, Object> reply(String text, String model) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(model, "model");
        return Map.of(
                "role", "assistant",
                "content", Map.of("type", "text", "text", text),
                "model", model,
                "stopReason", "endTurn");
    }

    @SuppressWarnings("unchecked")
    private static Optional<ChatMessage> convert(Map<String, Object> message) {
        String role = message.get("role") instanceof String value ? value : "";
        Object content = message.get("content");
        String text;
        if (content instanceof Map<?, ?> block) {
            Map<String, Object> typed = (Map<String, Object>) block;
            // Anything that is not text — an image, an audio block — is dropped rather than
            // forwarded. A server has no way to have obtained one the user consented to send.
            if (!"text".equals(typed.get("type")) || !(typed.get("text") instanceof String value)) {
                return Optional.empty();
            }
            text = value;
        } else if (content instanceof String value) {
            text = value;
        } else {
            return Optional.empty();
        }
        if (text.isBlank()) {
            return Optional.empty();
        }
        String bounded = bound(text, MAX_MESSAGE_CHARS);
        // A role that is neither is dropped, not guessed at: `system` belongs in systemPrompt,
        // and anything else is a server speaking a protocol this client does not.
        return Optional.ofNullable(switch (role) {
            case "assistant" -> ChatMessage.assistant(bounded);
            case "user" -> ChatMessage.user(bounded);
            default -> null;
        });
    }

    private static String bound(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "\n[truncated]";
    }

    /** Turns one text block into the content shape a server sends. Used by tests and fixtures. */
    public static Map<String, Object> textBlock(String text) {
        return Map.of("type", "text", "text", Objects.requireNonNull(text, "text"));
    }

    /** Whether a content block would survive {@link #requestFor}. */
    public static boolean isForwardable(ContentBlock block) {
        return block instanceof ContentBlock.Text;
    }
}
