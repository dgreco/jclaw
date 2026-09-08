package io.jclaw.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.List;

/**
 * All externalized configuration, bound from {@code application.yaml}, environment, or CLI flags.
 *
 * <p>A record rather than a mutable bean: configuration is read once at startup and must not drift
 * underneath a running turn. Spring Boot binds records natively via the canonical constructor, so
 * immutability costs nothing here.
 *
 * @param workspace            root the agent may read and write; every path is confined to it
 * @param stateDir             where transcripts and the event log are written
 * @param model                model id passed to the provider
 * @param provider             {@code mock} or {@code anthropic}
 * @param approvalMode         {@code read-only}, {@code interactive}, or {@code trusted}
 * @param allowPrivateNetworks lets tools reach loopback and RFC1918 addresses. Development only —
 *                             it re-opens the SSRF surface the egress guard exists to close.
 * @param maxIterations        tick-cycle cap per run
 * @param maxTokens            token budget per run, 0 for unlimited
 * @param systemPrompt         system prompt prepended to every turn
 */
@ConfigurationProperties(prefix = "jclaw")
public record JclawProperties(

        @DefaultValue(".") Path workspace,

        @DefaultValue("${user.home}/.jclaw") Path stateDir,

        @DefaultValue("claude-opus-5") String model,

        @DefaultValue("mock") String provider,

        /** Base URL for the {@code openai} provider. Override for an OpenAI-compatible gateway. */
        @DefaultValue("https://api.openai.com/v1") String openaiBaseUrl,

        /** Base URL for a local Ollama daemon. */
        @DefaultValue("http://localhost:11434/v1") String ollamaBaseUrl,

        /**
         * Base URL for the {@code local} provider: any OpenAI-compatible inference server —
         * LM Studio ({@code http://localhost:1234/v1}), vLLM ({@code http://localhost:8000/v1}),
         * llama.cpp's server, LocalAI. Blank means not configured: the {@code local} provider
         * refuses to start without it (there is no sensible default port to guess), and the
         * failover chain omits the local hop entirely.
         */
        @DefaultValue("") String localBaseUrl,

        /** Base URL for OpenRouter. Override only for a proxy or a self-hosted gateway. */
        @DefaultValue("https://openrouter.ai/api/v1") String openrouterBaseUrl,

        /**
         * Optional {@code HTTP-Referer} sent to OpenRouter, which uses it to attribute traffic to
         * an app on its dashboards. Blank means the header is omitted entirely.
         */
        @DefaultValue("") String openrouterReferer,

        /** Optional {@code X-Title} sent to OpenRouter. Blank means the header is omitted. */
        @DefaultValue("jclaw") String openrouterTitle,

        @DefaultValue("interactive") String approvalMode,

        @DefaultValue("false") boolean allowPrivateNetworks,

        @DefaultValue("25") int maxIterations,

        @DefaultValue("500000") long maxTokens,

        @DefaultValue("You are jclaw, a helpful agent operating inside a bounded workspace. "
                + "Use the provided tools when they help. Be concise and factual.")
        String systemPrompt,

        /**
         * Turns for the {@code mock} provider, replayed in order. Lets the whole CLI — including
         * tool calls and the approval flow — be exercised with no API key and no network, which is
         * the same property that makes the mock provider useful in tests.
         *
         * <p>Each entry is either {@code text:<reply>} or
         * {@code tool:<capability>:<k=v,k=v>}. Ignored unless the provider is {@code mock}.
         */
        @DefaultValue("") List<String> mockScript) {

    /**
     * Properties with every default applied, for programmatic construction.
     *
     * <p>Exists so callers that only care about a path or two do not have to list every component
     * positionally — a canonical-constructor call site breaks on each new property, which is a
     * poor reason for a test to fail.
     */
    public static JclawProperties defaults(Path workspace, Path stateDir) {
        return new JclawProperties(
                workspace,
                stateDir,
                "claude-opus-5",
                "mock",
                "https://api.openai.com/v1",
                "http://localhost:11434/v1",
                "",
                "https://openrouter.ai/api/v1",
                "",
                "jclaw",
                "interactive",
                false,
                25,
                500_000,
                "You are jclaw, a helpful agent operating inside a bounded workspace.",
                List.of());
    }

    /** Where the append-only event log lives. */
    public Path eventLogPath() {
        return stateDir.resolve("events.jsonl");
    }

    /** Where the durable transcript lives. */
    public Path transcriptPath() {
        return stateDir.resolve("transcript.jsonl");
    }

    /** Approval gates and their decisions. Durable so a gate survives the process that raised it. */
    public Path approvalsPath() {
        return stateDir.resolve("approvals.jsonl");
    }

    /** Loop checkpoints. Durable so a parked run can be resumed by a later process. */
    public Path checkpointsPath() {
        return stateDir.resolve("checkpoints.jsonl");
    }

    /** Run records and their resolved profiles. */
    public Path runsPath() {
        return stateDir.resolve("runs.jsonl");
    }

    /** Durable memories. */
    public Path memoryPath() {
        return stateDir.resolve("memory.jsonl");
    }

    /** Directory holding skill packages, one subdirectory each. */
    public Path skillsPath() {
        return stateDir.resolve("skills");
    }

    /** Scheduled routines. */
    public Path routinesPath() {
        return stateDir.resolve("routines.jsonl");
    }

    /** Configured MCP servers. */
    public Path mcpPath() {
        return stateDir.resolve("mcp.jsonl");
    }

    /** Persistent REPL line history, so Up recalls prompts from earlier sessions. */
    public Path replHistoryPath() {
        return stateDir.resolve("repl-history");
    }

    /** Whether the configured provider needs outbound credentials. */
    public boolean requiresCredentials() {
        return credentialEnvVar().isPresent();
    }

    /**
     * The environment variable the active provider reads its credential from.
     *
     * <p>Empty for providers that need none — the mock, a local Ollama daemon, and the generic
     * {@code local} server (whose {@code LOCAL_API_KEY} is honoured when present but never
     * required, since most local servers listen unauthenticated). Kept here so {@code doctor} and
     * {@code models} report the same answer as the wiring actually uses; a hard-coded check
     * elsewhere would drift the moment a provider is added.
     */
    public java.util.Optional<String> credentialEnvVar() {
        return switch (provider) {
            case "anthropic" -> java.util.Optional.of("ANTHROPIC_API_KEY");
            case "openai" -> java.util.Optional.of("OPENAI_API_KEY");
            case "openrouter" -> java.util.Optional.of("OPENROUTER_API_KEY");
            // The failover chain builds itself from whatever keys are present, so no single
            // variable is required for it to start.
            case "mock", "ollama", "local", "failover" -> java.util.Optional.empty();
            default -> java.util.Optional.empty();
        };
    }
}
