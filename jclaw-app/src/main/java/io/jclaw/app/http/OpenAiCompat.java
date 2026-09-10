// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.http;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Translation between the OpenAI chat-completions wire shape and jclaw's vocabulary, for the
 * {@code /v1/chat/completions} route.
 *
 * <p>Pure mapping, kept apart from the server so it can be tested with plain maps. Two shapes
 * of client exist and both are served:
 *
 * <ul>
 *   <li><b>Stateless clients</b> (most OpenAI SDK usage) send the whole conversation every call.
 *       Their prior turns are replayed into a fresh jclaw thread so the agent sees them, and only
 *       the last user message becomes the new turn.</li>
 *   <li><b>Stateful clients</b> name a thread with {@code X-Jclaw-Thread}; jclaw is the memory,
 *       and only the last user message is taken from the request.</li>
 * </ul>
 *
 * <p>Client {@code system} messages are ignored: the operator's configured system prompt governs
 * what the agent is, and a holder of the API token should not be able to rewrite it per call.
 */
final class OpenAiCompat {

    private OpenAiCompat() {
    }

    /** What a request asks for. */
    record Parsed(
            List<ChatMessage> priorTurns,
            ChatMessage inbound,
            boolean stream,
            Optional<String> model) {
    }

    /** Parses a chat-completions body. Fails with a reason when there is no user message to run. */
    @SuppressWarnings("unchecked")
    static Parsed parse(Map<String, Object> body) {
        Objects.requireNonNull(body, "body");
        List<ChatMessage> turns = new ArrayList<>();
        if (body.get("messages") instanceof List<?> messages) {
            for (Object raw : messages) {
                if (raw instanceof Map<?, ?> m) {
                    toMessage((Map<String, Object>) m).ifPresent(turns::add);
                }
            }
        }
        int last = -1;
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role() == ChatMessage.Role.USER) {
                last = i;
                break;
            }
        }
        if (last < 0) {
            throw new IllegalArgumentException("messages must contain a user message");
        }
        boolean stream = body.get("stream") instanceof Boolean b && b;
        Optional<String> model = body.get("model") instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
        return new Parsed(List.copyOf(turns.subList(0, last)), turns.get(last), stream, model);
    }

    /** One wire message to a jclaw message; system messages and empty content are dropped. */
    @SuppressWarnings("unchecked")
    private static Optional<ChatMessage> toMessage(Map<String, Object> m) {
        String role = String.valueOf(m.get("role"));
        ChatMessage.Role mapped = switch (role) {
            case "user" -> ChatMessage.Role.USER;
            case "assistant" -> ChatMessage.Role.ASSISTANT;
            default -> null;
        };
        if (mapped == null) {
            return Optional.empty();
        }
        List<ContentBlock> blocks = new ArrayList<>();
        Object content = m.get("content");
        if (content instanceof String text) {
            if (!text.isBlank()) {
                blocks.add(new ContentBlock.Text(text));
            }
        } else if (content instanceof List<?> parts) {
            for (Object raw : parts) {
                if (!(raw instanceof Map<?, ?> part)) {
                    continue;
                }
                Map<String, Object> p = (Map<String, Object>) part;
                String type = String.valueOf(p.get("type"));
                if (type.equals("text") && p.get("text") instanceof String text && !text.isBlank()) {
                    blocks.add(new ContentBlock.Text(text));
                } else if (type.equals("image_url") && p.get("image_url") instanceof Map<?, ?> image) {
                    dataUrl(String.valueOf(((Map<String, Object>) image).get("url"))).ifPresent(blocks::add);
                }
            }
        }
        return blocks.isEmpty() ? Optional.empty() : Optional.of(new ChatMessage(mapped, blocks));
    }

    /** {@code data:image/png;base64,…} to an image block; remote URLs are not fetched. */
    static Optional<ContentBlock> dataUrl(String url) {
        if (!url.startsWith("data:")) {
            return Optional.empty();
        }
        int comma = url.indexOf(',');
        if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) {
            return Optional.empty();
        }
        String mediaType = url.substring("data:".length(), comma - ";base64".length());
        try {
            return Optional.of(new ContentBlock.Image(mediaType, url.substring(comma + 1)));
        } catch (IllegalArgumentException unsupported) {
            return Optional.empty();
        }
    }

    /** A non-streaming completion. */
    static Map<String, Object> completion(
            String id, long created, String model, String content, String finishReason,
            long promptTokens, long completionTokens) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("object", "chat.completion");
        out.put("created", created);
        out.put("model", model);
        out.put("choices", List.of(choice));
        out.put("usage", usage(promptTokens, completionTokens));
        return out;
    }

    /** One streaming chunk; {@code content} null for the terminating chunk. */
    static Map<String, Object> chunk(String id, long created, String model, String content, String finishReason) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (content != null) {
            delta.put("content", content);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("object", "chat.completion.chunk");
        out.put("created", created);
        out.put("model", model);
        out.put("choices", List.of(choice));
        return out;
    }

    static Map<String, Object> usage(long promptTokens, long completionTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        return usage;
    }

    static Map<String, Object> error(String message, String type) {
        return Map.of("error", Map.of("message", message, "type", type));
    }
}
