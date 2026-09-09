package io.jclaw.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
 * @param contextMaxMessages   most recent transcript messages a model request may carry
 * @param contextMaxTokens     estimated token budget (4 chars/token) for those messages; lower it
 *                             for local models with small context windows
 * @param systemPrompt         system prompt prepended to every turn
 * @param embeddingProvider    {@code none} (default), {@code openai}, {@code openrouter},
 *                             {@code ollama}, or {@code local}; adds vector similarity to memory
 *                             retrieval. Credentials come from the same environment variables as
 *                             the chat providers
 * @param embeddingModel       embedding model id; blank picks the provider's default
 * @param deniedCapabilities   capability ids the kernel refuses outright in every mode, e.g.
 *                             {@code builtin.shell}; they also disappear from the surface the
 *                             model sees
 * @param egressAllowlist      when non-empty, tools may only reach these hosts (exact or
 *                             {@code *.suffix}); private-network and metadata denials still apply
 * @param egressDenylist       hosts tools may never reach, on top of the built-in metadata hosts
 * @param injectionPolicy      what the kernel does with tool output that looks like a prompt
 *                             injection: {@code off}, {@code warn}, {@code sanitize} (default), or
 *                             {@code block}
 * @param contextSummarise     whether history the context window drops is summarised by a model
 *                             call before each request, rather than only counted
 * @param contextSummaryMaxTokens output cap for that summary call
 * @param approvalTtl          how long an unanswered approval or auth gate stays answerable; a
 *                             resume after that asks afresh
 * @param toolEgress           per-capability egress allowlists, capability id to a comma-separated
 *                             host list ({@code *.suffix} allowed); applied on top of the host guard
 * @param toolRateLimits       per-capability invocation caps, capability id to {@code N/window}
 *                             ({@code 5/1m}, {@code 100/1h}); enforced per process
 * @param subagentsAsync       when true a subagent is queued for a worker and the parent parks
 *                             {@code WAITING_PROCESS}; when false (default) the child runs inside
 *                             the parent's tool call. Async needs a running worker or server
 * @param retentionResults     maximum age of a finished run's rows in {@code results.jsonl};
 *                             {@code 0} keeps forever
 * @param retentionEvents      the same for {@code events.jsonl}
 * @param retentionCheckpoints the same for {@code checkpoints.jsonl}
 * @param serveToken           the operator's bearer token for {@code jclaw serve}; blank means no
 *                             authentication (only sensible on loopback). The operator is the
 *                             {@code local} tenant, sharing threads with the CLI, and may read
 *                             every tenant's runs
 * @param serveUsers           named users for {@code jclaw serve}, user name to bearer token; each
 *                             user is a tenant of their own with separate threads, memories,
 *                             approvals, and a scheduler share
 * @param shellBackend         {@code host} (default) runs {@code builtin.shell} as a child
 *                             process; {@code docker} runs it in a container per
 *                             {@code jclaw.sandbox-*}
 * @param sandboxDocker        the docker (or compatible) binary to invoke
 * @param sandboxImage         the image commands run in
 * @param sandboxNetwork       container network: {@code none} (default) or a docker network name
 * @param sandboxMemory        container memory limit, e.g. {@code 512m}
 * @param sandboxCpus          container CPU limit, e.g. {@code 1}
 * @param sandboxPidsLimit     container pid limit
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

        @DefaultValue("200") int contextMaxMessages,

        @DefaultValue("100000") int contextMaxTokens,

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
        @DefaultValue("") List<String> mockScript,

        @DefaultValue("none") String embeddingProvider,

        @DefaultValue("") String embeddingModel,

        @DefaultValue("") List<String> deniedCapabilities,

        @DefaultValue("") List<String> egressAllowlist,

        @DefaultValue("") List<String> egressDenylist,

        @DefaultValue("sanitize") String injectionPolicy,

        @DefaultValue("true") boolean contextSummarise,

        @DefaultValue("1024") int contextSummaryMaxTokens,

        @DefaultValue("24h") Duration approvalTtl,

        Map<String, String> toolEgress,

        Map<String, String> toolRateLimits,

        @DefaultValue("false") boolean subagentsAsync,

        @DefaultValue("14d") Duration retentionResults,

        @DefaultValue("30d") Duration retentionEvents,

        @DefaultValue("7d") Duration retentionCheckpoints,

        @DefaultValue("") String serveToken,

        Map<String, String> serveUsers,

        @DefaultValue("host") String shellBackend,

        @DefaultValue("docker") String sandboxDocker,

        @DefaultValue("alpine:3.20") String sandboxImage,

        @DefaultValue("none") String sandboxNetwork,

        @DefaultValue("512m") String sandboxMemory,

        @DefaultValue("1") String sandboxCpus,

        @DefaultValue("256") int sandboxPidsLimit) {

    public JclawProperties {
        // Constructor binding leaves an absent map null; an absent map means no limits.
        toolEgress = toolEgress == null ? Map.of() : Map.copyOf(toolEgress);
        toolRateLimits = toolRateLimits == null ? Map.of() : Map.copyOf(toolRateLimits);
        serveUsers = serveUsers == null ? Map.of() : Map.copyOf(serveUsers);
    }

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
                200,
                100_000,
                "You are jclaw, a helpful agent operating inside a bounded workspace.",
                List.of(),
                "none",
                "",
                List.of(),
                List.of(),
                List.of(),
                "sanitize",
                true,
                1024,
                Duration.ofHours(24),
                Map.of(),
                Map.of(),
                false,
                Duration.ofDays(14),
                Duration.ofDays(30),
                Duration.ofDays(7),
                "",
                Map.of(),
                "host",
                "docker",
                "alpine:3.20",
                "none",
                "512m",
                "1",
                256);
    }

    /**
     * A list property with blanks removed.
     *
     * <p>Spring binds an absent list property to a single blank element rather than an empty
     * list, so every list here has to be read through this or a default would deny the
     * capability named "".
     */
    public static List<String> nonBlank(List<String> values) {
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    /**
     * The embedding model to use: the configured one, or the provider's conventional default.
     *
     * <p>Defaults exist for the hosted and Ollama providers because each has one obvious choice.
     * {@code local} has none, since the model is whatever the operator loaded, so it must be set.
     */
    public String resolvedEmbeddingModel() {
        if (!embeddingModel.isBlank()) {
            return embeddingModel;
        }
        return switch (embeddingProvider) {
            case "openai" -> "text-embedding-3-small";
            case "openrouter" -> "openai/text-embedding-3-small";
            case "ollama" -> "nomic-embed-text";
            default -> "";
        };
    }

    /** The environment variable the embedding provider reads its credential from, if any. */
    public java.util.Optional<String> embeddingCredentialEnvVar() {
        return switch (embeddingProvider) {
            case "openai" -> java.util.Optional.of("OPENAI_API_KEY");
            case "openrouter" -> java.util.Optional.of("OPENROUTER_API_KEY");
            default -> java.util.Optional.empty();
        };
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

    /** Full capability result payloads, the evidence behind result refs. */
    public Path resultsPath() {
        return stateDir.resolve("results.jsonl");
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

    /** Per-thread run locks. OS file locks, so a crashed worker's lock dies with it. */
    public Path locksPath() {
        return stateDir.resolve("locks");
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
