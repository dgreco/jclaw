package io.jclaw.domain.prompt;

/**
 * How much conversation a model request may carry.
 *
 * <p>Part of the run profile, alongside model and system prompt: it shapes what the model sees on
 * every call, so it is resolved once per run rather than read from configuration mid-flight.
 *
 * <p>Two limits, and the tighter one binds. {@code maxMessages} is the ceiling IronClaw's fixed
 * window had; {@code maxInputTokens} is what actually keeps a long thread inside the model's
 * context. Tokens are <em>estimated</em>, at four characters per token, because a real tokenizer
 * is model-specific and this policy has to be pure and provider-neutral. The estimate errs
 * generous for English prose and tight for code; leave headroom under the model's real limit.
 *
 * @param maxMessages    most recent messages retained per request
 * @param maxInputTokens estimated token budget for the retained messages
 */
public record ContextPolicy(int maxMessages, int maxInputTokens) {

    /**
     * Sensible for current frontier models (200k+ contexts): a hundred thousand estimated tokens
     * of history leaves room for the system prompt, tools, and the reply. Local models with 8k
     * or 32k contexts need a much smaller {@code maxInputTokens}.
     */
    public static final ContextPolicy DEFAULT = new ContextPolicy(200, 100_000);

    public ContextPolicy {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be positive, got " + maxMessages);
        }
        if (maxInputTokens <= 0) {
            throw new IllegalArgumentException("maxInputTokens must be positive, got " + maxInputTokens);
        }
    }
}
