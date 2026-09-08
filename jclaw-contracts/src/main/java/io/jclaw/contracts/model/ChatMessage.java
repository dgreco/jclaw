package io.jclaw.contracts.model;

import java.util.List;
import java.util.Objects;

/**
 * One message in a model conversation.
 *
 * @param role    who authored it
 * @param content ordered content blocks; a message is a sequence, not a string
 */
public record ChatMessage(Role role, List<ContentBlock> content) {

    /** Author of a message. System prompts are separated from the turn list by most providers. */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        /** Carries {@link ContentBlock.ToolResult} blocks back to the model. */
        TOOL
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, List.of(new ContentBlock.Text(text)));
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.Text(text)));
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, List.of(new ContentBlock.Text(text)));
    }

    public static ChatMessage toolResults(List<ContentBlock.ToolResult> results) {
        return new ChatMessage(Role.TOOL, List.copyOf(results));
    }

    /** Concatenated displayable text. Tool calls and reasoning are excluded by construction. */
    public String displayText() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.Text text) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    /** Tool calls requested by this message, in order. */
    public List<ContentBlock.ToolUse> toolUses() {
        return content.stream()
                .filter(ContentBlock.ToolUse.class::isInstance)
                .map(ContentBlock.ToolUse.class::cast)
                .toList();
    }

    public boolean hasToolUses() {
        return content.stream().anyMatch(ContentBlock.ToolUse.class::isInstance);
    }

    /** Rough character weight of the whole message. */
    public int weight() {
        return content.stream().mapToInt(ContentBlock::weight).sum();
    }
}
