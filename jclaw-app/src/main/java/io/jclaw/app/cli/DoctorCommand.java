package io.jclaw.app.cli;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.kernel.capability.CapabilityPolicy;
import io.jclaw.kernel.guard.EgressGuard;
import io.jclaw.kernel.guard.WorkspaceGuard;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Configuration and environment diagnostics.
 *
 * <p>Reports the security posture as prominently as the plumbing. A harness running with private
 * networks enabled, or auto-approving process execution, is in a materially different threat
 * position than the defaults — and the person running it should be able to see that without
 * reading the config file.
 *
 * <p>Exits non-zero when something is actually broken, so it is usable as a CI preflight.
 */
@Component
@Command(
        name = "doctor",
        description = "Check configuration, workspace, credentials, and security posture.",
        mixinStandardHelpOptions = true)
public class DoctorCommand implements Callable<Integer> {

    private final JclawProperties properties;
    private final WorkspaceGuard workspace;
    private final EgressGuard egress;
    private final CapabilityPolicy policy;
    private final io.jclaw.contracts.secret.SecretVault vault;
    private final io.jclaw.app.config.StorageBackend backend;
    private final io.jclaw.contracts.extension.ExtensionRegistry extensions;
    private final io.jclaw.app.channel.ChannelService channels;

    /**
     * Deliberately does <b>not</b> inject {@link io.jclaw.contracts.model.ModelProvider}.
     *
     * <p>Provider beans fail construction when their credential is missing, which would mean the
     * one command whose job is to diagnose a broken configuration could not start on a broken
     * configuration. Everything doctor reports about the provider is configuration, so it reads
     * configuration.
     */
    public DoctorCommand(
            JclawProperties properties,
            WorkspaceGuard workspace,
            EgressGuard egress,
            CapabilityPolicy policy,
            io.jclaw.contracts.secret.SecretVault vault,
            io.jclaw.app.config.StorageBackend backend,
            io.jclaw.contracts.extension.ExtensionRegistry extensions,
            io.jclaw.app.channel.ChannelService channels) {
        this.channels = channels;
        this.properties = properties;
        this.workspace = workspace;
        this.egress = egress;
        this.policy = policy;
        this.vault = vault;
        this.backend = backend;
        this.extensions = extensions;
    }

    @Override
    public Integer call() {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        System.out.println("jclaw doctor");
        System.out.println();

        System.out.println("Configuration");
        System.out.println("  workspace       " + workspace.root());
        System.out.println("  state dir       " + properties.stateDir());
        System.out.println("  storage         " + backend.describe());
        System.out.println("  provider        " + properties.provider());
        System.out.println("  model           " + properties.model());
        System.out.println("  approval mode   " + properties.approvalMode());
        System.out.println("  embeddings      " + ("none".equals(properties.embeddingProvider())
                ? "none"
                : properties.embeddingProvider() + " (" + properties.resolvedEmbeddingModel() + ")"));

        System.out.println();
        System.out.println("Security posture");
        System.out.println("  auto-approve up to   " + policy.autoApproveCeiling());
        System.out.println("  interactive gates    " + (policy.interactive() ? "enabled" : "disabled"));
        System.out.println("  private networks     "
                + (egress.privateNetworksAllowed() ? "ALLOWED" : "blocked"));
        System.out.println("  injection policy     " + policy.injection().name().toLowerCase(java.util.Locale.ROOT));
        System.out.println("  approval ttl         " + properties.approvalTtl());
        System.out.println("  context summaries    " + (properties.contextSummarise() ? "on" : "off"));
        System.out.println("  subagents            " + (properties.subagentsAsync() ? "async (needs a worker)" : "sync"));
        System.out.println("  serve auth           " + ((properties.serveToken() == null || properties.serveToken().isBlank())
                && properties.serveUsers().isEmpty()
                ? "none (loopback only)"
                : (properties.serveToken() != null && !properties.serveToken().isBlank() ? "operator token" : "no operator token")
                        + ", " + properties.serveUsers().size() + " user(s)"));
        System.out.println("  secret vault         " + vault.list().size() + " secret(s), key from "
                + (System.getenv("JCLAW_VAULT_KEY") == null || System.getenv("JCLAW_VAULT_KEY").isBlank()
                        ? "key file " + properties.vaultKeyPath() : "JCLAW_VAULT_KEY"));
        System.out.println("  shell backend        " + ("docker".equals(properties.shellBackend())
                ? "docker (" + properties.sandboxImage() + ", network " + properties.sandboxNetwork()
                        + ", " + properties.sandboxMemory() + ", " + properties.sandboxCpus() + " cpu)"
                : "host (no sandbox)"));
        System.out.println("  channels             " + (channels.enabled()
                ? String.join(", ", new java.util.TreeSet<>(channels.channels()))
                : "none"));
        System.out.println("  observability        metrics at /metrics on serve"
                + (properties.otlpEndpoint() == null || properties.otlpEndpoint().isBlank()
                        ? ", no trace export" : ", traces to " + properties.otlpEndpoint()));
        System.out.println("  loop family          " + properties.loopFamily()
                + (JclawProperties.nonBlank(properties.hooks()).isEmpty() ? ", no hooks"
                        : ", hooks " + String.join(", ", JclawProperties.nonBlank(properties.hooks()))));
        System.out.println("  extensions           " + extensions.list().size() + " installed ("
                + extensions.list().stream().filter(e -> e.trust() == io.jclaw.contracts.capability.TrustClass.VERIFIED).count()
                + " verified), " + properties.trustedPublishers().size() + " trusted publisher(s)");
        System.out.println("  mcp backend          " + io.jclaw.app.config.JclawConfiguration.mcpSandboxSpec(properties)
                .map(spec -> "docker (" + spec.image() + ", network " + spec.network() + ")")
                .orElse("host (servers unsandboxed)")
                + (properties.mcpLazy() ? ", started on first use" : ", started at boot"));
        System.out.println("  retention            results " + properties.retentionResults()
                + ", events " + properties.retentionEvents() + ", checkpoints " + properties.retentionCheckpoints()
                + " (0 = keep forever; transcript is never swept)");
        System.out.println("  per-tool limits      " + (policy.toolEgress().isEmpty() && policy.rateLimits().isEmpty()
                ? "none"
                : policy.toolEgress().size() + " egress allowlist(s), " + policy.rateLimits().size() + " rate limit(s)"));
        System.out.println("  denied capabilities  " + (policy.denied().isEmpty() ? "none"
                : policy.denied().stream().map(id -> id.value()).sorted()
                        .collect(java.util.stream.Collectors.joining(", "))));
        System.out.println("  egress allowlist     " + (egress.allowlistedHosts().isEmpty()
                ? "none (any public host)" : String.join(", ", egress.allowlistedHosts())));
        System.out.println("  egress denylist      " + (egress.denylistedHosts().isEmpty()
                ? "metadata hosts only" : String.join(", ", egress.denylistedHosts())));

        if (egress.privateNetworksAllowed()) {
            warnings.add("private networks are reachable by tools; this re-opens the SSRF surface "
                    + "and should never be enabled outside local development");
        }
        if (policy.autoApproveCeiling().atLeast(io.jclaw.contracts.capability.EffectClass.PROCESS)) {
            warnings.add("shell execution runs without approval; a prompt injection becomes "
                    + ("docker".equals(properties.shellBackend())
                            ? "code execution inside the sandbox container in this mode"
                            : "arbitrary code execution on this host in this mode; consider jclaw.shell-backend=docker"));
        }

        System.out.println();
        System.out.println("Checks");
        check("workspace is a readable directory",
                Files.isDirectory(workspace.root()) && Files.isReadable(workspace.root()),
                problems, "workspace " + workspace.root() + " is not a readable directory");
        check("state directory is writable",
                Files.isWritable(properties.stateDir()),
                problems, "state directory " + properties.stateDir() + " is not writable");

        // Ask the configuration which variable matters rather than assuming Anthropic: a user
        // running openrouter was previously told to set ANTHROPIC_API_KEY, which is simply wrong.
        properties.credentialEnvVar().ifPresentOrElse(
                variable -> {
                    boolean present = hasEnv(variable)
                            // Anthropic also accepts an OAuth profile via ANTHROPIC_AUTH_TOKEN.
                            || ("ANTHROPIC_API_KEY".equals(variable) && hasEnv("ANTHROPIC_AUTH_TOKEN"));
                    check("provider credentials present", present, problems,
                            "provider '" + properties.provider() + "' needs " + variable
                                    + ("ANTHROPIC_API_KEY".equals(variable)
                                            ? " (or an OAuth profile)" : ""));
                },
                () -> System.out.println(
                        "  [skip] provider '" + properties.provider() + "' needs no credentials"));

        properties.embeddingCredentialEnvVar().ifPresent(variable ->
                check("embedding credentials present", hasEnv(variable), problems,
                        "embedding provider '" + properties.embeddingProvider() + "' needs " + variable));

        System.out.println();
        if (!warnings.isEmpty()) {
            warnings.forEach(warning -> System.out.println("  [warn] " + warning));
            System.out.println();
        }
        if (problems.isEmpty()) {
            System.out.println("All checks passed.");
            return 0;
        }
        problems.forEach(problem -> System.err.println("  [fail] " + problem));
        return 1;
    }

    private static boolean hasEnv(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static void check(String label, boolean ok, List<String> problems, String failure) {
        System.out.println("  [" + (ok ? " ok " : "fail") + "] " + label);
        if (!ok) {
            problems.add(failure);
        }
    }
}
