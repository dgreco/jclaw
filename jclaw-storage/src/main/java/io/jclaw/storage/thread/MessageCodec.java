package io.jclaw.storage.thread;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit mapping between {@link ChatMessage} and JSON-ready maps.
 *
 * <p>Hand-written for the same reasons as the event codec: no reflection for native image, a wire
 * format that survives record refactoring, and — the one that matters most here — deliberate
 * control over what is persisted.
 *
 * <p>{@link ContentBlock.Thinking} is <b>not</b> written to the transcript. Provider reasoning is
 * sensitive, is not user-visible content, and has no business surviving in a durable conversation
 * log. It round-trips within a single run's in-memory message list and stops there. Dropping it
 * here rather than at every call site is what makes that reliable.
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    /** Encodes a message. Reasoning blocks are omitted by design. */
    public static Map<String, Object> encode(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            encodeBlock(block).ifPresent(blocks::add);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", message.role().name());
        out.put("content", blocks);
        return out;
    }

    private static java.util.Optional<Map<String, Object>> encodeBlock(ContentBlock block) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (block) {
            case ContentBlock.Text text -> {
                out.put("kind", "text");
                out.put("text", text.text());
            }
            case ContentBlock.ToolUse use -> {
                out.put("kind", "tool_use");
                out.put("callId", use.callId());
                out.put("name", use.name());
                out.put("input", use.input());
            }
            case ContentBlock.ToolResult result -> {
                out.put("kind", "tool_result");
                out.put("callId", result.callId());
                out.put("content", result.content());
                out.put("isError", result.isError());
            }
            case ContentBlock.Thinking ignored -> {
                return java.util.Optional.empty(); // never persisted
            }
        }
        return java.util.Optional.of(out);
    }

    /** Decodes a message. Unknown block kinds are skipped for forward compatibility. */
    @SuppressWarnings("unchecked")
    public static ChatMessage decode(Map<String, Object> record) {
        Objects.requireNonNull(record, "record");
        ChatMessage.Role role = ChatMessage.Role.valueOf(String.valueOf(record.get("role")));

        List<ContentBlock> blocks = new ArrayList<>();
        Object rawContent = record.get("content");
        if (rawContent instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    decodeBlock((Map<String, Object>) map).ifPresent(blocks::add);
                }
            }
        }
        return new ChatMessage(role, blocks);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Optional<ContentBlock> decodeBlock(Map<String, Object> map) {
        String kind = String.valueOf(map.get("kind"));
        return java.util.Optional.ofNullable(switch (kind) {
            case "text" -> new ContentBlock.Text(String.valueOf(map.get("text")));
            case "tool_use" -> new ContentBlock.ToolUse(
                    String.valueOf(map.get("callId")),
                    String.valueOf(map.get("name")),
                    map.get("input") instanceof Map<?, ?> input
                            ? (Map<String, Object>) input
                            : Map.of());
            case "tool_result" -> new ContentBlock.ToolResult(
                    String.valueOf(map.get("callId")),
                    String.valueOf(map.get("content")),
                    map.get("isError") instanceof Boolean flag && flag);
            default -> null;
        });
    }
}
