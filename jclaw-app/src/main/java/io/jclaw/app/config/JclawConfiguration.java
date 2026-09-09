package io.jclaw.app.config;

import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityHost;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityResultStore;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.loop.CheckpointStore;
import io.jclaw.app.runtime.McpRegistry;
import io.jclaw.contracts.mcp.McpServerStore;
import io.jclaw.contracts.memory.EmbeddingProvider;
import io.jclaw.contracts.memory.MemoryStore;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.routine.RoutineStore;
import io.jclaw.contracts.capability.SubagentHost;
import io.jclaw.contracts.skill.SkillCatalog;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.domain.loop.LoopStateCodec;
import io.jclaw.domain.policy.RateLimit;
import io.jclaw.kernel.capability.CapabilityPolicy;
import io.jclaw.kernel.capability.DefaultCapabilityHost;
import io.jclaw.kernel.capability.GuardedHandlerContext;
import io.jclaw.kernel.capability.InjectionPolicy;
import io.jclaw.kernel.guard.EgressGuard;
import io.jclaw.kernel.guard.WorkspaceGuard;
import io.jclaw.loop.EffectInterpreter;
import io.jclaw.providers.anthropic.AnthropicModelProvider;
import io.jclaw.providers.failover.FailoverModelProvider;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.providers.openai.OpenAiCompatibleEmbeddingProvider;
import io.jclaw.providers.openai.OpenAiCompatibleModelProvider;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadLock;
import io.jclaw.storage.approval.JsonlApprovalStore;
import io.jclaw.storage.checkpoint.JsonlCheckpointStore;
import io.jclaw.storage.checkpoint.JsonLoopStateCodec;
import io.jclaw.storage.event.JsonlEventLog;
import io.jclaw.storage.lock.FileThreadLock;
import io.jclaw.storage.mcp.JsonlMcpServerStore;
import io.jclaw.storage.mcp.McpSurfaceCache;
import io.jclaw.storage.memory.JsonlMemoryStore;
import io.jclaw.storage.skill.FilesystemSkillCatalog;
import io.jclaw.storage.result.JsonlCapabilityResultStore;
import io.jclaw.storage.routine.JsonlRoutineStore;
import io.jclaw.storage.run.JsonlRunStore;
import io.jclaw.storage.extension.FilesystemExtensionRegistry;
import io.jclaw.contracts.loop.LoopHook;
import io.jclaw.kernel.capability.CapabilityPolicyResolver;
import io.jclaw.app.observability.ObservedEventLog;
import io.jclaw.app.observability.OtlpExporter;
import io.jclaw.app.observability.Telemetry;
import io.jclaw.app.runtime.BudgetNoticeHook;
import io.jclaw.contracts.extension.ExtensionRegistry;
import io.jclaw.storage.rows.RowStore;
import io.jclaw.storage.sql.SqlSchema;
import io.jclaw.storage.secret.FileSecretVault;
import io.jclaw.storage.secret.VaultKey;
import io.jclaw.contracts.secret.SecretVault;
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
import java.util.Map;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JclawConfiguration.class);

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
        EgressGuard guard = properties.allowPrivateNetworks()
                ? EgressGuard.allowingPrivateNetworks()
                : EgressGuard.publicOnly();
        // Lists from configuration can only narrow the posture: an allowlist restricts, a
        // denylist adds, and neither touches the address-family or metadata checks.
        return guard
                .withAllowlist(Set.copyOf(JclawProperties.nonBlank(properties.egressAllowlist())))
                .withDenylist(Set.copyOf(JclawProperties.nonBlank(properties.egressDenylist())));
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
            JclawProperties properties, Clock clock, MemoryStore memoryStore,
            EmbeddingProvider embeddingProvider, SkillCatalog skillCatalog, SubagentHost subagentHost,
            RoutineStore routineStore, McpRegistry mcp,
            io.jclaw.app.runtime.WasmExtensionHost wasmExtensions) {
        List<CapabilityHandler> handlers = new ArrayList<>(CoreTools.all(clock));
        handlers.addAll(FileTools.all());
        handlers.addAll(MemoryTools.all(memoryStore, clock, embeddingProvider));
        handlers.addAll(SkillTools.all(skillCatalog));
        handlers.addAll(TriggerTools.all(routineStore));
        handlers.add(new ShellTool(sandboxSpec(properties)));
        handlers.add(new HttpTool());
        handlers.add(new SubagentTool(subagentHost, properties.subagentsAsync()));
        // External tools last: they are third-party and must never shadow a built-in. The
        // kernel rejects duplicate ids outright, and the mcp.* namespace makes collision
        // impossible anyway.
        handlers.addAll(mcp.handlers());
        // WASM extensions last, beside MCP: third-party, namespaced, and never shadowing a built-in.
        handlers.addAll(wasmExtensions.handlers());
        return List.copyOf(handlers);
    }

    /**
     * The shell lane's container contract, when configured.
     *
     * <p>{@code host} is the default because it needs nothing installed; {@code docker} is the
     * posture for anything that runs commands the operator does not read first. A misspelt
     * backend fails startup rather than silently running on the host.
     */
    static java.util.Optional<io.jclaw.domain.sandbox.SandboxSpec> sandboxSpec(JclawProperties properties) {
        return switch (properties.shellBackend()) {
            case "host" -> java.util.Optional.empty();
            case "docker" -> java.util.Optional.of(new io.jclaw.domain.sandbox.SandboxSpec(
                    properties.sandboxDocker(), properties.sandboxImage(), properties.sandboxNetwork(),
                    properties.sandboxMemory(), properties.sandboxCpus(), properties.sandboxPidsLimit(), true));
            default -> throw new IllegalArgumentException(
                    "unknown jclaw.shell-backend '" + properties.shellBackend() + "'; expected host or docker");
        };
    }

    /**
     * The MCP lane's container contract, when configured.
     *
     * <p>An MCP server is third-party code with its own network stack; on the host it can reach
     * anything. {@code jclaw.mcp-backend=docker} starts each server inside the shell sandbox's
     * contract, with its own image and network setting since servers usually need a runtime
     * (node, python) and sometimes the network the tool exists to reach.
     */
    public static java.util.Optional<io.jclaw.domain.sandbox.SandboxSpec> mcpSandboxSpec(JclawProperties properties) {
        return switch (properties.mcpBackend()) {
            case "host" -> java.util.Optional.empty();
            case "docker" -> java.util.Optional.of(new io.jclaw.domain.sandbox.SandboxSpec(
                    properties.sandboxDocker(),
                    properties.mcpSandboxImage().isBlank() ? properties.sandboxImage() : properties.mcpSandboxImage(),
                    properties.mcpSandboxNetwork().isBlank() ? properties.sandboxNetwork() : properties.mcpSandboxNetwork(),
                    properties.sandboxMemory(), properties.sandboxCpus(), properties.sandboxPidsLimit(), true));
            default -> throw new IllegalArgumentException(
                    "unknown jclaw.mcp-backend '" + properties.mcpBackend() + "'; expected host or docker");
        };
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
    public McpServerStore mcpServerStore(JclawProperties properties, StorageBackend backend) {
        return new JsonlMcpServerStore(backend.open("mcp", properties.mcpPath()));
    }

    /** Connects to configured MCP servers. A no-op when none are configured. */
    @Bean
    public McpRegistry mcpRegistry(
            McpServerStore mcpServerStore, WorkspaceGuard workspaceGuard, JclawProperties properties,
            ExtensionRegistry extensionRegistry, EgressGuard egressGuard, SecretVault secretVault,
            McpSurfaceCache mcpSurfaceCache) {
        return new McpRegistry(mcpServerStore, workspaceGuard.root(), mcpSandboxSpec(properties),
                extensionRegistry, egressGuard, secretVault,
                properties.mcpLazy() ? mcpSurfaceCache : null);
    }

    /**
     * The posture per tenant.
     *
     * <p>A tenant policy may pick a stricter approval mode and add denials; it inherits the
     * host's denials whatever it says, so configuring a tenant can only narrow what the process
     * already permits.
     */
    @Bean
    public CapabilityPolicyResolver capabilityPolicyResolver(
            JclawProperties properties, CapabilityPolicy capabilityPolicy) {
        java.util.Map<String, CapabilityPolicy> byTenant = new java.util.LinkedHashMap<>();
        properties.tenantPolicies().forEach((tenant, tenantPolicy) -> {
            CapabilityPolicy posture = tenantPolicy.approvalMode().isBlank()
                    ? capabilityPolicy
                    : posture(tenantPolicy.approvalMode());
            java.util.Set<io.jclaw.contracts.capability.CapabilityId> denied =
                    new java.util.LinkedHashSet<>(capabilityPolicy.denied());
            JclawProperties.nonBlank(tenantPolicy.deniedCapabilities())
                    .forEach(id -> denied.add(io.jclaw.contracts.capability.CapabilityId.of(id)));
            byTenant.put(tenant, posture
                    .withDenied(denied)
                    .withRateLimits(capabilityPolicy.rateLimits())
                    .withToolEgress(capabilityPolicy.toolEgress())
                    .withInjection(capabilityPolicy.injection()));
        });
        return byTenant.isEmpty()
                ? CapabilityPolicyResolver.fixed(capabilityPolicy)
                : CapabilityPolicyResolver.byTenant(capabilityPolicy, byTenant);
    }


    /** One of the three postures, by name. The only place a mode string becomes a policy. */
    static CapabilityPolicy posture(String approvalMode) {
        return switch (approvalMode) {
            case "read-only" -> CapabilityPolicy.unattended();
            case "trusted" -> CapabilityPolicy.trustedLocal();
            case "interactive" -> CapabilityPolicy.interactiveDefault();
            default -> throw new IllegalArgumentException(
                    "unknown approval mode '" + approvalMode
                            + "'; expected read-only, interactive, or trusted");
        };
    }

    /** Tokens spent per tenant, and whether a tenant may start another turn. */
    @Bean
    public io.jclaw.app.runtime.TenantLedger tenantLedger(
            EventLog eventLog, RunStore runStore, JclawProperties properties) {
        return new io.jclaw.app.runtime.TenantLedger(eventLog, runStore, properties.tenantTokenBudget());
    }

    /**
     * Hands the ledger to the runtime once both exist.
     *
     * <p>A tiny bean whose construction is the wiring: the runtime writes the events the ledger
     * reads, so they cannot be constructor arguments of one another.
     */
    @Bean
    public LedgerWiring ledgerWiring(
            io.jclaw.app.runtime.JclawRuntime runtime, io.jclaw.app.runtime.TenantLedger tenantLedger) {
        runtime.withLedger(tenantLedger);
        return new LedgerWiring();
    }

    /** Marker for the wiring above. */
    public static final class LedgerWiring { }

    /**
     * The WebAssembly extensions installed here.
     *
     * <p>The spec is the operator's: how much memory and computation any module may have. The
     * permissions are the package's, and they can only open host functions this host implements,
     * each already behind a guard.
     */
    @Bean
    public io.jclaw.app.runtime.WasmExtensionHost wasmExtensionHost(
            ExtensionRegistry extensionRegistry, JclawProperties properties) {
        io.jclaw.domain.wasm.WasmSpec spec = new io.jclaw.domain.wasm.WasmSpec(
                properties.wasmMaxMemoryPages(), properties.wasmMaxInstructions(),
                properties.wasmMaxOutputBytes(), properties.wasmTimeout(), java.util.Set.of());
        return new io.jclaw.app.runtime.WasmExtensionHost(
                extensionRegistry, properties.extensionsPath(), spec);
    }

    /** Sessions issued by logging in, stored as hashes. */
    @Bean
    public io.jclaw.contracts.identity.SessionStore sessionStore(
            JclawProperties properties, StorageBackend backend, Clock clock) {
        return new io.jclaw.storage.identity.JsonlSessionStore(
                backend.open("sessions", properties.sessionsPath()), clock);
    }

    /**
     * The OpenID Connect provider, when one is configured. Absent otherwise, which is what turns
     * the login routes into a 404 rather than an endpoint that fails confusingly. The absence is
     * carried by {@link io.jclaw.app.identity.LoginProvider} rather than an {@code Optional}
     * because a bean method may not return one; that class explains why.
     */
    @Bean
    public io.jclaw.app.identity.LoginProvider oidcLogin(JclawProperties properties, Clock clock) {
        return properties.oidcConfigured()
                ? io.jclaw.app.identity.LoginProvider.of(new io.jclaw.app.identity.OidcLogin(
                        properties.oidcIssuer(), properties.oidcClientId(), clock))
                : io.jclaw.app.identity.LoginProvider.none();
    }

    /**
     * Extensions available from a registry, rather than from a directory on this machine.
     *
     * <p>Empty unless {@code jclaw.extension-registries} names one, which is what keeps a default
     * installation from reaching the network to answer {@code extensions list}.
     */
    @Bean
    public io.jclaw.app.extension.ExtensionCatalog extensionCatalog(
            JclawProperties properties, EgressGuard egressGuard) {
        return new io.jclaw.app.extension.ExtensionCatalog(properties.extensionRegistries(), egressGuard);
    }

    /** The messaging channels jclaw can be talked to from. Empty unless configured. */
    @Bean
    public java.util.List<io.jclaw.contracts.channel.ChannelAdapter> channelAdapters(Clock clock) {
        return java.util.List.of(
                new io.jclaw.app.channel.SlackAdapter(clock),
                new io.jclaw.app.channel.TelegramAdapter());
    }

    @Bean
    public io.jclaw.contracts.channel.ChannelBindingStore channelBindingStore(
            JclawProperties properties, StorageBackend backend, Clock clock) {
        return new io.jclaw.storage.channel.JsonlChannelBindingStore(
                backend.open("channel-bindings", properties.channelBindingsPath()), clock);
    }

    /**
     * Wires the channel loop. Registering it as a listener on the event log is what delivers a
     * reply after the run that produced it finishes, however long that took.
     */
    @Bean
    public io.jclaw.app.channel.ChannelService channelService(
            java.util.List<io.jclaw.contracts.channel.ChannelAdapter> channelAdapters,
            JclawProperties properties,
            io.jclaw.contracts.channel.ChannelBindingStore channelBindingStore,
            io.jclaw.app.runtime.JclawRuntime runtime, ThreadService threadService, RunStore runStore,
            SecretVault secretVault, EgressGuard egressGuard, EventLog eventLog) {

        java.util.Map<String, io.jclaw.app.channel.ChannelService.Credentials> credentials =
                new java.util.LinkedHashMap<>();
        properties.channels().forEach((id, secrets) -> {
            if (!secrets.complete()) {
                throw new IllegalArgumentException(
                        "jclaw.channels." + id + " needs both verify-secret and token");
            }
            credentials.put(id, new io.jclaw.app.channel.ChannelService.Credentials(
                    secrets.verifySecret(), secrets.token()));
        });
        return new io.jclaw.app.channel.ChannelService(channelAdapters, credentials, channelBindingStore,
                runtime, threadService, runStore, secretVault, egressGuard, eventLog);
    }

    /** What each MCP server offered last time, so the next start need not ask again. */
    @Bean
    public McpSurfaceCache mcpSurfaceCache(JclawProperties properties, StorageBackend backend, Clock clock) {
        return new McpSurfaceCache(backend.open("mcp-surface", properties.mcpSurfacePath()), clock);
    }

    /**
     * Installed extension packages. Exposed as the concrete type too so the {@code sign}
     * subcommand can compute a package digest the same way an install does.
     */
    @Bean
    public FilesystemExtensionRegistry extensionRegistry(
            JclawProperties properties, StorageBackend backend, FilesystemSkillCatalog skillCatalog, Clock clock) {
        return new FilesystemExtensionRegistry(
                properties.extensionsPath(),
                backend.open("extensions", properties.extensionsJsonlPath()),
                properties.trustedPublishers(),
                java.util.Optional.of(skillCatalog.root()),
                clock);
    }

    /** Scheduled routines. Nothing fires them on its own — see RoutineRunner. */
    @Bean
    public RoutineStore routineStore(JclawProperties properties, StorageBackend backend, Clock clock) {
        return new JsonlRoutineStore(backend.open("routines", properties.routinesPath()), clock);
    }

    /** Durable memories, scoped per project by the store itself. */
    @Bean
    public MemoryStore memoryStore(JclawProperties properties, StorageBackend backend, Clock clock) {
        return new JsonlMemoryStore(backend.open("memory", properties.memoryPath()), clock);
    }

    /**
     * Embedding provider for memory retrieval. {@code none} by default: vector ranking is an
     * addition to lexical and recency ranking, and a default that required a running embedding
     * server would break {@code jclaw memory} for everyone who has not set one up.
     *
     * <p>Like the chat providers, credentials are resolved per request, so a missing key is a
     * degraded search rather than a context that refuses to start.
     */
    @Bean
    public EmbeddingProvider embeddingProvider(JclawProperties properties) {
        String model = properties.resolvedEmbeddingModel();
        return switch (properties.embeddingProvider()) {
            case "none" -> EmbeddingProvider.disabled();
            case "openai" -> OpenAiCompatibleEmbeddingProvider.openai(
                    model, System.getenv("OPENAI_API_KEY"));
            case "openrouter" -> OpenAiCompatibleEmbeddingProvider.openrouter(
                    model, System.getenv("OPENROUTER_API_KEY"), properties.openrouterBaseUrl(),
                    properties.openrouterReferer(), properties.openrouterTitle());
            case "ollama" -> OpenAiCompatibleEmbeddingProvider.ollama(properties.ollamaBaseUrl(), model);
            case "local" -> {
                if (properties.localBaseUrl().isBlank()) {
                    throw new IllegalArgumentException(
                            "jclaw.embedding-provider=local requires jclaw.local-base-url");
                }
                if (model.isBlank()) {
                    throw new IllegalArgumentException(
                            "jclaw.embedding-provider=local requires jclaw.embedding-model: "
                                    + "there is no default for a server that hosts whatever was loaded");
                }
                yield OpenAiCompatibleEmbeddingProvider.local(
                        properties.localBaseUrl(), model, System.getenv("LOCAL_API_KEY"));
            }
            default -> throw new IllegalArgumentException(
                    "unknown jclaw.embedding-provider '" + properties.embeddingProvider()
                            + "'; expected none, openai, openrouter, ollama, or local");
        };
    }

    @Bean
    public CapabilityPolicy capabilityPolicy(JclawProperties properties) {
        CapabilityPolicy posture = posture(properties.approvalMode());
        // Hard denials from configuration. Validated at startup: a typo here must fail loudly
        // rather than silently deny nothing.
        Set<CapabilityId> denied = new java.util.LinkedHashSet<>();
        for (String id : JclawProperties.nonBlank(properties.deniedCapabilities())) {
            try {
                denied.add(CapabilityId.of(id));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "jclaw.denied-capabilities entry '" + id + "' is not a capability id "
                                + "(expected e.g. builtin.shell)", e);
            }
        }
        Map<CapabilityId, Set<String>> toolEgress = new java.util.LinkedHashMap<>();
        properties.toolEgress().forEach((capability, hosts) -> toolEgress.put(
                capabilityId("jclaw.tool-egress", capability),
                Set.copyOf(JclawProperties.nonBlank(List.of(hosts.split(","))))));
        Map<CapabilityId, RateLimit> rateLimits = new java.util.LinkedHashMap<>();
        properties.toolRateLimits().forEach((capability, spec) -> {
            try {
                rateLimits.put(capabilityId("jclaw.tool-rate-limits", capability), RateLimit.parse(spec));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "jclaw.tool-rate-limits entry for '" + capability + "': " + e.getMessage(), e);
            }
        });
        return posture.withDenied(denied)
                .withInjection(InjectionPolicy.parse(properties.injectionPolicy()))
                .withToolEgress(toolEgress)
                .withRateLimits(rateLimits);
    }

    private static CapabilityId capabilityId(String property, String raw) {
        try {
            return CapabilityId.of(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    property + " key '" + raw + "' is not a capability id (expected e.g. builtin.shell)", e);
        }
    }

    /**
     * Where rows live. {@code jclaw.storage=sql} opens the database, applies pending schema
     * migrations, and routes every store to it; the default keeps JSONL files.
     *
     * <p>The database password is read from {@code JCLAW_DATASOURCE_PASSWORD} only. A URL is
     * topology and may sit in config; a password is a credential and may not.
     */
    @Bean
    public StorageBackend storageBackend(JclawProperties properties) {
        if (!"sql".equals(properties.storage())) {
            if (!"jsonl".equals(properties.storage())) {
                throw new IllegalArgumentException(
                        "jclaw.storage must be 'jsonl' or 'sql', got '" + properties.storage() + "'");
            }
            return StorageBackend.jsonl(properties.stateDir());
        }
        String url = properties.resolvedDatasourceUrl();
        String password = System.getenv("JCLAW_DATASOURCE_PASSWORD");
        // Pooled rather than a connection per call. For the CLI the difference is invisible —
        // one process, one turn — but `serve` runs several turns at once, and against
        // PostgreSQL each unpooled call costs a TCP round trip and a new backend process.
        com.zaxxer.hikari.HikariConfig pool = new com.zaxxer.hikari.HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(properties.datasourceUsername());
        pool.setPassword(password == null ? "" : password);
        pool.setMaximumPoolSize(Math.max(1, properties.datasourcePoolSize()));
        // A worker holding a connection while a model call is in flight would be a bug, not a
        // slow query, so a short timeout surfaces it as an error instead of a hang.
        pool.setConnectionTimeout(java.time.Duration.ofSeconds(10).toMillis());
        pool.setPoolName("jclaw");
        com.zaxxer.hikari.HikariDataSource dataSource = new com.zaxxer.hikari.HikariDataSource(pool);
        int before = SqlSchema.migrate(dataSource);
        log.debug("storage: sql at {} (schema {} -> {}, pool max {})",
                redactUrl(url), before, SqlSchema.currentVersion(), pool.getMaximumPoolSize());
        return StorageBackend.sql(dataSource, redactUrl(url));
    }

    /** A JDBC URL without any userinfo or password parameter, for display. */
    static String redactUrl(String url) {
        return url.replaceAll("//[^/@]+@", "//")
                .replaceAll("(?i)(password=)[^;&]*", "$1[REDACTED]");
    }

    @Bean
    public RowStore eventLogFile(JclawProperties properties, StorageBackend backend) {
        return backend.open("events", properties.eventLogPath());
    }

    /** Process metrics, fed by every event the log accepts. */
    @Bean
    public Telemetry telemetry() {
        return new Telemetry();
    }

    /**
     * Trace export, when {@code jclaw.otlp-endpoint} names a collector. The bean is the closer
     * too, so queued exports get a moment to drain at shutdown.
     */
    @Bean
    public OtlpExporters otlpExporter(JclawProperties properties) {
        String endpoint = properties.otlpEndpoint();
        return new OtlpExporters(endpoint == null || endpoint.isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(new OtlpExporter(endpoint.trim(), "jclaw")));
    }

    /** Holds the optional exporter so Spring has a bean to close at shutdown. */
    public record OtlpExporters(java.util.Optional<OtlpExporter> exporter) implements AutoCloseable {
        @Override
        public void close() {
            exporter.ifPresent(OtlpExporter::close);
        }
    }

    @Bean
    public EventLog eventLog(RowStore eventLogFile, Telemetry telemetry, OtlpExporters otlpExporter) {
        return new ObservedEventLog(new JsonlEventLog(eventLogFile), telemetry, otlpExporter.exporter());
    }

    @Bean
    public ThreadService threadService(JclawProperties properties, StorageBackend backend, Clock clock) {
        return new JsonlThreadService(backend.open("transcript", properties.transcriptPath()), clock);
    }

    /**
     * Durable, because the approval flow crosses process boundaries: {@code run} parks and exits,
     * a human approves in a second process, and a third resumes. An in-memory store loses the gate
     * at the first boundary.
     */
    @Bean
    public JsonlApprovalStore approvalStore(JclawProperties properties, StorageBackend backend, Clock clock) {
        return new JsonlApprovalStore(backend.open("approvals", properties.approvalsPath()), clock, properties.approvalTtl());
    }

    /**
     * Durable, because result refs are evidence the runtime re-resolves before trusting an exit,
     * and a run resumed in a second process completes with refs the first process minted.
     */
    @Bean
    public CapabilityResultStore capabilityResultStore(JclawProperties properties, StorageBackend backend, Clock clock) {
        return new JsonlCapabilityResultStore(backend.open("results", properties.resultsPath()), clock);
    }

    /** Durable for the same reason: a parked run is resumed by a different process. */
    @Bean
    public CheckpointStore checkpointStore(JclawProperties properties, StorageBackend backend, Clock clock) {
        return new JsonlCheckpointStore(backend.open("checkpoints", properties.checkpointsPath()), clock);
    }

    /** Records each run's resolved profile so a resume replays it rather than re-deriving it. */
    @Bean
    public RunStore runStore(JclawProperties properties, StorageBackend backend, Clock clock) {
        return new JsonlRunStore(backend.open("runs", properties.runsPath()), clock);
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

    /**
     * Secrets the model may reference by name, encrypted at rest.
     *
     * <p>The key comes from {@code JCLAW_VAULT_KEY} when set, otherwise from an owner-only key
     * file generated beside the vault on first use. Only the kernel host and the {@code secrets}
     * command hold this bean: tool lanes and providers are barred from it by the dependency law.
     */
    @Bean
    public io.jclaw.app.runtime.TenantVaults tenantVaults(
            JclawProperties properties, StorageBackend backend, Clock clock) {
        byte[] key = VaultKey.parse(System.getenv("JCLAW_VAULT_KEY"))
                .orElseGet(() -> VaultKey.loadOrCreate(properties.vaultKeyPath()));
        return new io.jclaw.app.runtime.TenantVaults(backend, properties.secretsPath(), key, clock);
    }

    /**
     * The default tenant's vault: what {@code jclaw secrets} reads and writes.
     *
     * <p>The CLI has one tenant, so this is the whole vault there. Under {@code serve} it is one
     * of several, and the kernel picks per run from the scope rather than from this bean.
     */
    @Bean
    public SecretVault secretVault(io.jclaw.app.runtime.TenantVaults vaults) {
        return vaults.primary();
    }

    /**
     * Materialised run projections, when the storage backend has somewhere to put them.
     *
     * <p>JSONL gets {@link io.jclaw.storage.projection.RunProjectionCache#none()}: a file store
     * has no cheaper place to keep a fold than the log it would be folded from, so the honest
     * answer there is to keep folding.
     */
    @Bean
    public io.jclaw.storage.projection.RunProjectionCache runProjectionCache(
            StorageBackend backend, Clock clock) {
        return backend.dataSource()
                .<io.jclaw.storage.projection.RunProjectionCache>map(source ->
                        new io.jclaw.storage.projection.JdbcRunProjectionCache(source, clock))
                .orElseGet(io.jclaw.storage.projection.RunProjectionCache::none);
    }

    @Bean
    public CapabilityHost capabilityHost(
            List<CapabilityHandler> capabilityHandlers,
            ApprovalStore approvalStore,
            CapabilityResultStore capabilityResultStore,
            EventLog eventLog,
            CapabilityPolicyResolver capabilityPolicyResolver,
            CapabilityHandler.HandlerContext handlerContext,
            io.jclaw.app.runtime.TenantVaults tenantVaults,
            Clock clock) {

        return new DefaultCapabilityHost(
                capabilityHandlers,
                approvalStore,
                capabilityResultStore,
                eventLog,
                capabilityPolicyResolver,
                handlerContext,
                // Credentials the redactor should mask if a tool ever echoes them back. Read
                // lazily so a key exported after startup is still covered.
                () -> credentialValues(),
                tenantVaults,
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
            ApprovalStore approvalStore,
            ThreadService threadService,
            CheckpointStore checkpointStore,
            EventLog eventLog,
            LoopStateCodec loopStateCodec,
            Clock clock,
            List<LoopHook> loopHooks) {

        return new EffectInterpreter(
                modelProvider, capabilityHost, approvalStore, threadService,
                checkpointStore, eventLog, loopStateCodec, loopHooks, clock);
    }

    /**
     * The hooks that run before and after every model call and capability dispatch: the built-in
     * ones named in {@code jclaw.hooks}, then any {@link LoopHook} bean the application defines,
     * which is the seam a plugin uses.
     */
    @Bean
    public List<LoopHook> loopHooks(JclawProperties properties, java.util.Optional<List<LoopHook>> extraHooks,
            SecretVault secretVault) {
        List<LoopHook> hooks = new ArrayList<>();
        for (String id : JclawProperties.nonBlank(properties.hooks())) {
            switch (id) {
                case BudgetNoticeHook.ID -> hooks.add(new BudgetNoticeHook());
                case io.jclaw.app.runtime.SecretLeakHook.ID ->
                        hooks.add(new io.jclaw.app.runtime.SecretLeakHook(secretVault));
                default -> throw new IllegalArgumentException(
                        "unknown hook '" + id + "' in jclaw.hooks; known: " + BudgetNoticeHook.ID
                                + ", " + io.jclaw.app.runtime.SecretLeakHook.ID);
            }
        }
        extraHooks.ifPresent(hooks::addAll);
        return List.copyOf(hooks);
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
                    for (String pair : splitArguments(parts[1])) {
                        int equals = pair.indexOf('=');
                        if (equals < 0) {
                            throw new IllegalArgumentException(
                                    "jclaw.mock-script entry " + index + ": argument '" + pair
                                            + "' is not k=v");
                        }
                        arguments.put(pair.substring(0, equals).trim(),
                                parseArgumentValue(pair.substring(equals + 1), index));
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

    /**
     * Splits {@code k=v,k=v} on commas that are not inside a JSON value.
     *
     * <p>A flat split was enough while every argument was a string. It stopped being enough when
     * a capability took a map — {@code secret_env={"A":"x","B":"y"}} would become three fragments,
     * two of them not {@code k=v} — which made the whole staging path unreachable from the CLI,
     * and the mock script is how the CLI is meant to be exercised without a network.
     */
    static List<String> splitArguments(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                inString = c != '"' || (i > 0 && text.charAt(i - 1) == '\\');
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** A value is a string unless it opens a JSON object or array, in which case it is decoded. */
    static Object parseArgumentValue(String value, int index) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return value;
        }
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(trimmed, Object.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "jclaw.mock-script entry " + index + ": '" + trimmed + "' is not valid JSON");
        }
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
