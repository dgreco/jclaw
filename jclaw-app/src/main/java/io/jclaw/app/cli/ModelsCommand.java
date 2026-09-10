// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.contracts.memory.EmbeddingProvider;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Shows which model providers are configured, and optionally proves one works.
 *
 * <p>The {@code --probe} flag is the useful half. "Is my key set" is answerable by reading the
 * environment; "will a turn actually succeed" is only answerable by making a request, and that is
 * the question someone runs this command to settle.
 */
@Component
@Command(
        name = "models",
        description = "Show configured providers and optionally probe the active one.",
        mixinStandardHelpOptions = true)
public class ModelsCommand implements Callable<Integer> {

    /** Kept tiny: a probe should cost a token or two, not a turn's worth. */
    private static final int PROBE_MAX_TOKENS = 16;

    private final JclawProperties properties;
    private final ModelProvider provider;
    private final EmbeddingProvider embeddings;

    @Option(names = "--probe", description = "Send a minimal request to verify the provider works.")
    private boolean probe;

    public ModelsCommand(JclawProperties properties, ModelProvider provider, EmbeddingProvider embeddings) {
        this.properties = properties;
        this.provider = provider;
        this.embeddings = embeddings;
    }

    @Override
    public Integer call() {
        System.out.println("Active provider: " + provider.id());
        System.out.println("Model:           " + properties.model());
        System.out.println("Embeddings:      " + (embeddings.available()
                ? embeddings.id() + " (" + embeddings.model() + ")"
                : "none (set jclaw.embedding-provider to add vector ranking to memory search)"));
        System.out.println();

        System.out.println("Providers");
        row("mock", true, "no credentials needed; replies from a script");
        row("anthropic", hasEnv("ANTHROPIC_API_KEY") || hasEnv("ANTHROPIC_AUTH_TOKEN"),
                "ANTHROPIC_API_KEY or an OAuth profile");
        row("openai", hasEnv("OPENAI_API_KEY"), "OPENAI_API_KEY");
        row("openrouter", hasEnv("OPENROUTER_API_KEY"),
                "OPENROUTER_API_KEY; model ids are namespaced, e.g. anthropic/claude-sonnet-4.6");
        row("ollama", true, properties.ollamaBaseUrl() + " (no credentials)");
        row("local", !properties.localBaseUrl().isBlank(),
                properties.localBaseUrl().isBlank()
                        ? "set jclaw.local-base-url to any OpenAI-compatible server "
                                + "(LM Studio, vLLM, llama.cpp, ...)"
                        : properties.localBaseUrl() + " (LOCAL_API_KEY optional)");
        row("failover", true, "tries anthropic, openai, openrouter, local (if configured), then ollama");

        if (!probe) {
            System.out.println();
            System.out.println("Run with --probe to verify the active provider actually responds.");
            return 0;
        }

        System.out.println();
        System.out.println("Probing " + provider.id() + " with model " + properties.model() + "...");
        return provider.complete(new ModelRequest(
                        properties.model(),
                        "",
                        List.of(ChatMessage.user("Reply with the single word: ok")),
                        List.of(),
                        PROBE_MAX_TOKENS,
                        Optional.empty()))
                .fold(
                        response -> {
                            System.out.println("  ok - served by " + response.modelId()
                                    + " (" + response.usage().total() + " tokens)");
                            return 0;
                        },
                        failure -> {
                            System.err.println("  failed - " + failure.kind()
                                    + failure.detail().map(d -> ": " + d).orElse(""));
                            return 1;
                        });
    }

    private static void row(String name, boolean configured, String note) {
        System.out.printf("  %-10s %-14s %s%n", name, configured ? "configured" : "not configured", note);
    }

    private static boolean hasEnv(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}
