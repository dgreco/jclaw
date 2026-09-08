package io.jclaw.contracts.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The request/response vocabulary of a model call.
 *
 * <p>Grouped in one file because these types are meaningless apart — they are the two halves of a
 * single exchange plus the metadata describing it. Providers translate between this shape and
 * their own wire format; nothing above the provider layer knows what a provider's JSON looks like.
 */
public final class ModelExchange {

    private ModelExchange() {
    }

    /** A tool offered to the model. */
    public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
        public ToolSpec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
        }
    }

    /**
     * A model call.
     *
     * @param model       provider-specific model id
     * @param system      system prompt, kept separate from the turn list
     * @param messages    conversation so far
     * @param tools       capability surface visible to the model. Visibility is publication
     *                    metadata, never a grant — the kernel still authorizes each invocation.
     * @param maxTokens   output cap
     * @param temperature sampling temperature, absent to use the provider default
     */
    public record ModelRequest(
            String model,
            String system,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            int maxTokens,
            Optional<Double> temperature) {

        public ModelRequest {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(system, "system");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
            Objects.requireNonNull(temperature, "temperature");
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive, got " + maxTokens);
            }
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("messages must not be empty");
            }
        }

        public static ModelRequest of(String model, String system, List<ChatMessage> messages, int maxTokens) {
            return new ModelRequest(model, system, messages, List.of(), maxTokens, Optional.empty());
        }

        public ModelRequest withTools(List<ToolSpec> tools) {
            return new ModelRequest(model, system, messages, tools, maxTokens, temperature);
        }

        public ModelRequest withMessages(List<ChatMessage> messages) {
            return new ModelRequest(model, system, messages, tools, maxTokens, temperature);
        }
    }

    /** Why the model stopped generating. */
    public enum StopReason {
        /** Finished its reply. */
        END_TURN,
        /** Wants to call one or more tools; the loop must dispatch them and continue. */
        TOOL_USE,
        /** Hit the output cap mid-reply. */
        MAX_TOKENS,
        /** Matched a caller-supplied stop sequence. */
        STOP_SEQUENCE,
        /** Declined to answer. */
        REFUSAL;

        /** Whether the loop should continue the tick cycle rather than finish the turn. */
        public boolean continuesLoop() {
            return this == TOOL_USE;
        }
    }

    /** Token accounting for one exchange. Drives budget enforcement and cost reporting. */
    public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

        public static final Usage ZERO = new Usage(0, 0, 0, 0);

        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0) {
                throw new IllegalArgumentException("token counts must be non-negative");
            }
        }

        public static Usage of(long input, long output) {
            return new Usage(input, output, 0, 0);
        }

        public long total() {
            return inputTokens + outputTokens;
        }

        /** Accumulates across the exchanges of a run. */
        public Usage plus(Usage other) {
            return new Usage(
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    cacheReadTokens + other.cacheReadTokens,
                    cacheWriteTokens + other.cacheWriteTokens);
        }
    }

    /**
     * A model reply.
     *
     * @param message    the assistant message, including any tool-use blocks
     * @param stopReason why generation ended
     * @param usage      token accounting
     * @param modelId    the model that actually served the request, which may differ from the
     *                   requested id after failover — recorded so the route is auditable
     */
    public record ModelResponse(ChatMessage message, StopReason stopReason, Usage usage, String modelId) {
        public ModelResponse {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(stopReason, "stopReason");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(modelId, "modelId");
        }

        public List<ContentBlock.ToolUse> toolUses() {
            return message.toolUses();
        }

        public String text() {
            return message.displayText();
        }
    }
}
