package io.jclaw.app.config;

import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityHost;
import io.jclaw.contracts.capability.CapabilityResultStore;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.loop.CheckpointStore;
import io.jclaw.app.runtime.McpRegistry;
import io.jclaw.contracts.mcp.McpServerStore;
import io.jclaw.contracts.memory.MemoryStore;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.capability.SubagentHost;
import io.jclaw.contracts.skill.SkillCatalog;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.domain.loop.LoopStateCodec;
import io.jclaw.kernel.capability.CapabilityPolicy;
import io.jclaw.kernel.capability.DefaultCapabilityHost;
import io.jclaw.kernel.capability.GuardedHandlerContext;
import io.jclaw.kernel.guard.EgressGuard;
import io.jclaw.kernel.guard.WorkspaceGuard;
import io.jclaw.loop.EffectInterpreter;
import io.jclaw.providers.anthropic.AnthropicModelProvider;
import io.jclaw.providers.failover.FailoverModelProvider;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.providers.openai.OpenAiCompatibleModelProvider;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadLock;
import io.jclaw.storage.approval.JsonlApprovalStore;
import io.jclaw.storage.checkpoint.JsonlCheckpointStore;
import io.jclaw.storage.checkpoint.JsonLoopStateCodec;
import io.jclaw.storage.event.JsonlEventLog;
import io.jclaw.storage.jsonl.JsonlFile;
import io.jclaw.storage.lock.FileThreadLock;
import io.jclaw.storage.mcp.JsonlMcpServerStore;
import io.jclaw.storage.memory.JsonlMemoryStore;
import io.jclaw.storage.skill.FilesystemSkillCatalog;
import io.jclaw.storage.result.InMemoryCapabilityResultStore;
import io.jclaw.storage.routine.JsonlRoutineStore;
import io.jclaw.storage.run.JsonlRunStore;
import io.jclaw.storage.thread.JsonlThreadService;
import io.jclaw.tools.CoreTools;
import io.jclaw.tools.FileTools;
import io.jclaw.tools.HttpTool;
import io.jclaw.tools.MemoryTools;
import io.jclaw.tools.SkillTools;
import io.jclaw.tools.SubagentTool;
import io.jclaw.tools.TriggerTools;
import io.jclaw.tools.ShellTool;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The composition root. The only place in jclaw where concrete adapters are chosen and wired.
 *
 * <p>Every lower module depends on ports; this class is where those ports acquire implementations.
 * Swapping the model provider, the approval store, or the event log is a change here and nowhere
 * else — which is the practical payoff of the hexagonal layering rather than a slogan about it.
 *
 * <p>Constructor injection throughout, and every bean is immutable once built.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JclawProperties.class)
public class JclawConfiguration {

    /**
     * The single clock. Injected everywhere rather than read statically, so time is one
     * substitutable dependency instead of a hidden global in a dozen classes.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public WorkspaceGuard workspaceGuard(JclawProperties properties) {
        Path workspace = properties.workspace().toAbsolutePath().normalize();
        return WorkspaceGuard.rootedAt(workspace);
    }

    @Bean
    public EgressGuard egressGuard(JclawProperties properties) {
        // Private networks stay closed unless explicitly enabled: this is the SSRF boundary.
        return properties.allowPrivateNetworks()
                ? EgressGuard.allowingPrivateNetworks()
                : EgressGuard.publicOnly();
    }

    @Bean
    public CapabilityHandler.HandlerContext handlerContext(
            WorkspaceGuard workspaceGuard, EgressGuard egressGuard) {
        return new GuardedHandlerContext(workspaceGuard, egressGuard);
    }

    /**
     * Every built-in capability.
     *
     * <p>Registered as one list so the kernel can reject duplicate ids at startup rather than
     * silently letting a later registration shadow a built-in.
     */
    @Bean
    public List<CapabilityHandler> capabilityHandlers(
            Clock clock, MemoryStore memoryStore, SkillCatalog skillCatalog,
            SubagentHost subagentHost, RoutineStore routineStore, McpRegistry mcp) {
        List<CapabilityHandler> handlers = new ArrayList<>(CoreTools.all(clock));
        handlers.addAll(FileTools.all());
        handlers.addAll(MemoryTools.all(memoryStore, clock));
        handlers.addAll(SkillTools.all(skillCatalog));
        handlers.addAll(TriggerTools.all(routineStore));
        handlers.add(new ShellTool());
        handlers.add(new HttpTool());
        handlers.add(new SubagentTool(subagentHost));
        // External tools last: they are third-party and must never shadow a built-in. The
        // kernel rejects duplicate ids outright, and the mcp.* namespace makes collision
        // impossible anyway.
        handlers.addAll(mcp.handlers());
        return List.copyOf(handlers);
    }

    /**
     * Skills read from disk. Exposed as the concrete type as well so the CLI can report the
     * directory it reads from — useful when nothing is installed and the user needs to know where
     * to put one.
     */
    @Bean
    public FilesystemSkillCatalog skillCatalog(JclawProperties properties) {
        return new FilesystemSkillCatalog(properties.skillsPath());
    }

    @Bean
    public McpServerStore mcpServerStore(JclawProperties properties) {
        return new JsonlMcpServerStore(new JsonlFile(properties.mcpPath()));
    }

    /** Connects to configured MCP servers. A no-op when none are configured. */
    @Bean
    public McpRegistry mcpRegistry(McpServerStore mcpServerStore, WorkspaceGuard workspaceGuard) {
        return new McpRegistry(mcpServerStore, workspaceGuard.root());
    }

    /** Scheduled routines. Nothing fires them on its own — see RoutineRunner. */
    @Bean
    public RoutineStore routineStore(JclawProperties properties, Clock clock) {
        return new JsonlRoutineStore(new JsonlFile(properties.routinesPath()), clock);
    }

    /** Durable memories, scoped per project by the store itself. */
    @Bean
    public MemoryStore memoryStore(JclawProperties properties, Clock clock) {
        return new JsonlMemoryStore(new JsonlFile(properties.memoryPath()), clock);
    }

    @Bean
    public CapabilityPolicy capabilityPolicy(JclawProperties properties) {
        return switch (properties.approvalMode()) {
            case "read-only" -> CapabilityPolicy.unattended();
            case "trusted" -> CapabilityPolicy.trustedLocal();
            case "interactive" -> CapabilityPolicy.interactiveDefault();
            default -> throw new IllegalArgumentException(
                    "unknown jclaw.approval-mode '" + properties.approvalMode()
                            + "'; expected read-only, interactive, or trusted");
        };
    }

    @Bean
    public JsonlFile eventLogFile(JclawProperties properties) {
        return new JsonlFile(properties.eventLogPath());
    }

    @Bean
    public EventLog eventLog(JsonlFile eventLogFile) {
        return new JsonlEventLog(eventLogFile);
    }

    @Bean
    public ThreadService threadService(JclawProperties properties, Clock clock) {
        return new JsonlThreadService(new JsonlFile(properties.transcriptPath()), clock);
    }

    /**
     * Durable, because the approval flow crosses process boundaries: {@code run} parks and exits,
     * a human approves in a second process, and a third resumes. An in-memory store loses the gate
     * at the first boundary.
     */
    @Bean
    public JsonlApprovalStore approvalStore(JclawProperties properties, Clock clock) {
        return new JsonlApprovalStore(new JsonlFile(properties.approvalsPath()), clock);
    }

    @Bean
    public CapabilityResultStore capabilityResultStore(Clock clock) {
        return new InMemoryCapabilityResultStore(clock);
    }

    /** Durable for the same reason: a parked run is resumed by a different process. */
    @Bean
    public CheckpointStore checkpointStore(JclawProperties properties, Clock clock) {
        return new JsonlCheckpointStore(new JsonlFile(properties.checkpointsPath()), clock);
    }

    /** Records each run's resolved profile so a resume replays it rather than re-deriving it. */
    @Bean
    public RunStore runStore(JclawProperties properties, Clock clock) {
        return new JsonlRunStore(new JsonlFile(properties.runsPath()), clock);
    }

    /**
     * One active run per thread, enforced with OS file locks. The lock is taken before the inbound
     * message is made durable and dies with the process, so a crash leaves no thread locked.
     */
    @Bean
    public ThreadLock threadLock(JclawProperties properties) {
        return new FileThreadLock(properties.locksPath());
    }

    @Bean
    public LoopStateCodec loopStateCodec() {
        return new JsonLoopStateCodec();
    }

    @Bean
    public CapabilityHost capabilityHost(
            List<CapabilityHandler> capabilityHandlers,
            ApprovalStore approvalStore,
            CapabilityResultStore capabilityResultStore,
            EventLog eventLog,
            CapabilityPolicy capabilityPolicy,
            CapabilityHandler.HandlerContext handlerContext,
            Clock clock) {

        return new DefaultCapabilityHost(
                capabilityHandlers,
                approvalStore,
                capabilityResultStore,
                eventLog,
                capabilityPolicy,
                handlerContext,
                // Credentials the redactor should mask if a tool ever echoes them back. Read
                // lazily so a key exported after startup is still covered.
                () -> credentialValues(),
                clock);
    }

    /**
     * Model provider selection.
     *
     * <p>{@code mock} is the default so the harness runs, and its tests pass, with no API key and
     * no network. Choosing the real provider is deliberate rather than accidental.
     */
    @Bean
    public ModelProvider modelProvider(JclawProperties properties, Clock clock) {
        return switch (properties.provider()) {
            case "mock" -> properties.mockScript().stream().allMatch(String::isBlank)
                    ? MockModelProvider.alwaysReplying(
                            "This is the mock provider. Configure jclaw.provider=anthropic "
                                    + "and set ANTHROPIC_API_KEY to talk to a real model.")
                    : new MockModelProvider(parseMockScript(properties.mockScript()));
            case "anthropic" -> new AnthropicModelProvider();

            case "openai" -> OpenAiCompatibleModelProvider.openai(System.getenv("OPENAI_API_KEY"));

            case "openrouter" -> OpenAiCompatibleModelProvider.openrouter(
                    System.getenv("OPENROUTER_API_KEY"),
                    properties.openrouterBaseUrl(),
                    properties.openrouterReferer(),
                    properties.openrouterTitle());

            // Ollama needs no credentials and is expected to be on loopback.
            case "ollama" -> new OpenAiCompatibleModelProvider(
                    "ollama", properties.ollamaBaseUrl(), java.util.Optional.empty());

            // Any other local OpenAI-compatible server: LM Studio, vLLM, llama.cpp, LocalAI.
            // The base URL is required rather than defaulted because there is no port these
            // servers agree on — guessing one would produce a connection error that reads like
            // a jclaw bug instead of one line saying what to configure.
            case "local" -> {
                if (properties.localBaseUrl().isBlank()) {
                    throw new IllegalArgumentException(
                            "jclaw.provider=local requires jclaw.local-base-url, e.g. "
                                    + "http://localhost:1234/v1 (LM Studio) or "
                                    + "http://localhost:8000/v1 (vLLM)");
                }
                yield OpenAiCompatibleModelProvider.local(
                        properties.localBaseUrl(), System.getenv("LOCAL_API_KEY"));
            }

            case "failover" -> buildFailoverChain(properties, clock);

            default -> throw new IllegalArgumentException(
                    "unknown jclaw.provider '" + properties.provider()
                            + "'; expected mock, anthropic, openai, openrouter, ollama, local, "
                            + "or failover");
        };
    }

    /**
     * Builds a failover chain from whichever providers are actually configured.
     *
     * <p>Order is by capability, not preference-of-the-day: Anthropic first when a key exists,
     * then OpenAI, then OpenRouter as a broad gateway, then a configured local server (LM Studio,
     * vLLM, ...) when {@code jclaw.local-base-url} is set, then a local Ollama daemon as the
     * offline fallback. A provider with no credentials is omitted rather than added and left to
     * fail, because a chain that always fails its first hop just adds a timeout to every request.
     *
     * <p>OpenRouter sits late deliberately. It only claims namespaced {@code org/model} ids, so it
     * never competes with the first-party adapters for a bare id — but when it is reached it can
     * serve almost anything, which makes it the right last stop before falling back to local
     * inference.
     */
    private static ModelProvider buildFailoverChain(JclawProperties properties, Clock clock) {
        List<ModelProvider> chain = new ArrayList<>();
        if (System.getenv("ANTHROPIC_API_KEY") != null || System.getenv("ANTHROPIC_AUTH_TOKEN") != null) {
            chain.add(new AnthropicModelProvider());
        }
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            chain.add(OpenAiCompatibleModelProvider.openai(openaiKey));
        }
        String openrouterKey = System.getenv("OPENROUTER_API_KEY");
        if (openrouterKey != null && !openrouterKey.isBlank()) {
            chain.add(OpenAiCompatibleModelProvider.openrouter(
                    openrouterKey,
                    properties.openrouterBaseUrl(),
                    properties.openrouterReferer(),
                    properties.openrouterTitle()));
        }
        // A configured local server outranks Ollama in the chain: setting jclaw.local-base-url is
        // an explicit statement about where local inference lives, while the Ollama hop is a
        // default guess about what might be running.
        if (!properties.localBaseUrl().isBlank()) {
            chain.add(OpenAiCompatibleModelProvider.local(
                    properties.localBaseUrl(), System.getenv("LOCAL_API_KEY")));
        }
        chain.add(new OpenAiCompatibleModelProvider(
                "ollama", properties.ollamaBaseUrl(), java.util.Optional.empty()));
        return new FailoverModelProvider(chain, clock);
    }



    @Bean
    public EffectInterpreter effectInterpreter(
            ModelProvider modelProvider,
            CapabilityHost capabilityHost,
            ThreadService threadService,
            CheckpointStore checkpointStore,
            EventLog eventLog,
            LoopStateCodec loopStateCodec,
            Clock clock) {

        return new EffectInterpreter(
                modelProvider, capabilityHost, threadService,
                checkpointStore, eventLog, loopStateCodec, clock);
    }

    /**
     * Parses {@code jclaw.mock-script} entries into scripted turns.
     *
     * <p>Grammar is deliberately tiny: {@code text:<reply>} or
     * {@code tool:<capability>:<k=v,k=v>}. A malformed entry fails loudly at startup rather than
     * silently becoming a text reply, because a script that does not do what it reads as makes
     * every test written against it worthless.
     */
    private static List<MockModelProvider.Script> parseMockScript(List<String> entries) {
        List<MockModelProvider.Script> script = new ArrayList<>();
        int index = 0;
        for (String entry : entries) {
            index++;
            if (entry.isBlank()) {
                // Spring binds an absent list property to a single blank element, not an empty
                // list; treating that as a malformed entry would break every default run.
                continue;
            }
            if (entry.startsWith("text:")) {
                script.add(new MockModelProvider.Script.Text(entry.substring("text:".length())));
            } else if (entry.startsWith("tool:")) {
                String[] parts = entry.substring("tool:".length()).split(":", 2);
                java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<>();
                if (parts.length == 2 && !parts[1].isBlank()) {
                    for (String pair : parts[1].split(",")) {
                        int equals = pair.indexOf('=');
                        if (equals < 0) {
                            throw new IllegalArgumentException(
                                    "jclaw.mock-script entry " + index + ": argument '" + pair
                                            + "' is not k=v");
                        }
                        arguments.put(pair.substring(0, equals).trim(), pair.substring(equals + 1));
                    }
                }
                script.add(new MockModelProvider.Script.ToolCall("mock" + index, parts[0], arguments));
            } else {
                throw new IllegalArgumentException(
                        "jclaw.mock-script entry " + index + " must start with 'text:' or 'tool:', got: "
                                + entry);
            }
        }
        return List.copyOf(script);
    }

    /** Known credential values, masked wherever they appear in tool output. */
    private static Set<String> credentialValues() {
        Set<String> values = new java.util.LinkedHashSet<>();
        for (String name : List.of(
                "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "OPENAI_API_KEY", "OPENROUTER_API_KEY")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    /** Ensures the state directory exists before any store opens a file in it. */
    @Bean
    public StateDirectoryInitializer stateDirectoryInitializer(JclawProperties properties) {
        return new StateDirectoryInitializer(properties.stateDir());
    }

    /** Tiny bean whose construction has the side effect of creating the state directory. */
    public record StateDirectoryInitializer(Path stateDir) {
        public StateDirectoryInitializer {
            try {
                Files.createDirectories(stateDir);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot create state directory " + stateDir, e);
            }
        }
    }

    /** Effect ceiling currently in force, surfaced by {@code jclaw doctor}. */
    @Bean
    public EffectClass autoApprovalCeiling(CapabilityPolicy policy) {
        return policy.autoApproveCeiling();
    }
}
