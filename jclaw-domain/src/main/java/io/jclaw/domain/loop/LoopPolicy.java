package io.jclaw.domain.loop;

import io.jclaw.contracts.model.ModelExchange.ToolSpec;

import java.util.List;
import java.util.Objects;

/**
 * The resolved, immutable settings a run executes under.
 *
 * <p>Resolved once when the run is admitted and then carried unchanged, so a restart replays with
 * the same driver, model, and surface it started with. Re-resolving mid-run is how a resumed turn
 * silently acquires authority it was never granted.
 *
 * <p>Note what a policy is not: it is not a grant. Listing a tool in {@link #tools} publishes it
 * to the model; the kernel still authorizes every individual invocation.
 *
 * @param model                       provider-facing model id
 * @param systemPrompt                assembled system prompt
 * @param tools                       capability surface published to the model
 * @param maxOutputTokens             per-call output cap
 * @param maxConsecutiveModelFailures retries before a run gives up on a flaky provider
 */
public record LoopPolicy(
        String model,
        String systemPrompt,
        List<ToolSpec> tools,
        int maxOutputTokens,
        int maxConsecutiveModelFailures) {

    public LoopPolicy {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (maxConsecutiveModelFailures < 0) {
            throw new IllegalArgumentException("maxConsecutiveModelFailures must be non-negative");
        }
    }

    public static LoopPolicy of(String model, String systemPrompt, List<ToolSpec> tools) {
        return new LoopPolicy(model, systemPrompt, tools, 4096, 2);
    }

    public LoopPolicy withTools(List<ToolSpec> tools) {
        return new LoopPolicy(model, systemPrompt, tools, maxOutputTokens, maxConsecutiveModelFailures);
    }

    public LoopPolicy withSystemPrompt(String systemPrompt) {
        return new LoopPolicy(model, systemPrompt, tools, maxOutputTokens, maxConsecutiveModelFailures);
    }
}
