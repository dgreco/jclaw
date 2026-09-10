// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.loop;

import io.jclaw.contracts.model.ModelExchange.ToolSpec;
import io.jclaw.domain.prompt.ContextPolicy;

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
 * @param context                     how much history each model request may carry; applied by
 *                                    the machine when it builds a request, so the loop state and
 *                                    the transcript stay complete while the model's view is bounded
 */
public record LoopPolicy(
        String model,
        String systemPrompt,
        List<ToolSpec> tools,
        int maxOutputTokens,
        int maxConsecutiveModelFailures,
        ContextPolicy context,
        String family) {

    public LoopPolicy {
        Objects.requireNonNull(family, "family");
        // Only the shape is checked here. Whether a *configured* family exists is the
        // registry's question, and the registry is built by the application, not the domain —
        // a policy that validated against a static list could not carry an operator's own.
        if (family.isBlank()) {
            throw new IllegalArgumentException("a loop family id must not be blank");
        }
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        Objects.requireNonNull(context, "context");
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (maxConsecutiveModelFailures < 0) {
            throw new IllegalArgumentException("maxConsecutiveModelFailures must be non-negative");
        }
    }

    /** Convenience with the default context policy. */
    public LoopPolicy(
            String model,
            String systemPrompt,
            List<ToolSpec> tools,
            int maxOutputTokens,
            int maxConsecutiveModelFailures) {
        this(model, systemPrompt, tools, maxOutputTokens, maxConsecutiveModelFailures,
                ContextPolicy.DEFAULT);
    }

    public LoopPolicy(
            String model,
            String systemPrompt,
            List<ToolSpec> tools,
            int maxOutputTokens,
            int maxConsecutiveModelFailures,
            ContextPolicy context) {
        this(model, systemPrompt, tools, maxOutputTokens, maxConsecutiveModelFailures, context, "canonical");
    }

    /**
     * The strategy driving this run, resolved through a registry.
     *
     * <p>Takes the registry rather than looking the id up statically, which is what lets a
     * configured family exist at all: the domain knows the two that ship and nothing about an
     * operator's YAML.
     */
    public LoopFamily loopFamily(LoopFamilyRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return registry.byId(family).orElseThrow(
                () -> new IllegalStateException("unknown loop family '" + family + "'"));
    }

    /** The strategy, among the families that ship. */
    public LoopFamily loopFamily() {
        return loopFamily(LoopFamilyRegistry.builtIn());
    }

    public LoopPolicy withFamily(String family) {
        return new LoopPolicy(model, systemPrompt, tools, maxOutputTokens,
                maxConsecutiveModelFailures, context, family);
    }

    public static LoopPolicy of(String model, String systemPrompt, List<ToolSpec> tools) {
        return new LoopPolicy(model, systemPrompt, tools, 4096, 2);
    }

    public LoopPolicy withTools(List<ToolSpec> tools) {
        return new LoopPolicy(model, systemPrompt, tools, maxOutputTokens,
                maxConsecutiveModelFailures, context, family);
    }

    public LoopPolicy withSystemPrompt(String systemPrompt) {
        return new LoopPolicy(model, systemPrompt, tools, maxOutputTokens,
                maxConsecutiveModelFailures, context, family);
    }

    public LoopPolicy withContext(ContextPolicy context) {
        return new LoopPolicy(model, systemPrompt, tools, maxOutputTokens,
                maxConsecutiveModelFailures, context, family);
    }
}
