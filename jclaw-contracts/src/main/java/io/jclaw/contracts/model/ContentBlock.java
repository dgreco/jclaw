package io.jclaw.contracts.model;

import java.util.Map;
import java.util.Objects;

/**
 * One piece of message content.
 *
 * <p>Modelled as a sealed sum rather than a string because a modern turn is genuinely
 * heterogeneous: prose, tool calls, tool results, and provider-side reasoning all travel in the
 * same message and each needs different redaction and different handling. Flattening them to text
 * is how tool-call arguments end up in a transcript that was supposed to be safe to display.
 */
public sealed interface ContentBlock {

    /** Ordinary prose. */
    record Text(String text) implements ContentBlock {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * The model asking to invoke a capability.
     *
     * @param callId provider-assigned id, echoed back on the matching {@link ToolResult}
     * @param name   capability id, e.g. {@code builtin.read_file}
     * @param input  arguments as a plain map; kept structural so guards can inspect fields
     *               without re-parsing JSON, and so tool input never has to be logged to be
     *               understood
     */
    record ToolUse(String callId, String name, Map<String, Object> input) implements ContentBlock {
        public ToolUse {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(name, "name");
            input = Map.copyOf(Objects.requireNonNull(input, "input"));
        }
    }

    /**
     * The outcome of a capability, fed back to the model.
     *
     * @param isError whether the capability failed; the model is told <em>that</em> it failed and
     *                a safe summary, never the host-side cause
     */
    record ToolResult(String callId, String content, boolean isError) implements ContentBlock {
        public ToolResult {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(content, "content");
        }

        public static ToolResult ok(String callId, String content) {
            return new ToolResult(callId, content, false);
        }

        public static ToolResult error(String callId, String summary) {
            return new ToolResult(callId, summary, true);
        }
    }

    /**
     * Provider-side reasoning. Carried so it can round-trip where a provider requires it, but
     * treated as sensitive: never persisted to the transcript and never shown by default.
     */
    record Thinking(String text, String signature) implements ContentBlock {
        public Thinking {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(signature, "signature");
        }
    }

    /** Whether this block is safe to render in a user-visible transcript. */
    default boolean isDisplayable() {
        return this instanceof Text;
    }

    /** Rough character weight, used by the budget accountant before a real tokenizer runs. */
    default int weight() {
        return switch (this) {
            case Text t -> t.text().length();
            case ToolUse u -> u.name().length() + u.input().toString().length();
            case ToolResult r -> r.content().length();
            case Thinking t -> t.text().length();
        };
    }
}
