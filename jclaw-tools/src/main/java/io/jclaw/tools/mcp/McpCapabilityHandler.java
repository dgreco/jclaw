package io.jclaw.tools.mcp;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.HandlerError;
import io.jclaw.contracts.capability.TrustClass;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Adapts one MCP tool into a {@link CapabilityHandler}, so external tools travel the same
 * authorization path as built-ins.
 *
 * <p>The important lines in this file are the two classification arguments:
 *
 * <ul>
 *   <li>{@link TrustClass#COMMUNITY} — an MCP server is third-party code the user installed. Its
 *       auto-approval ceiling is {@code PURE}, and for third-party trust classes policy may only
 *       tighten. So <b>every MCP tool call requires human approval</b>, in every mode, including
 *       {@code trusted}. That is not a conservative default that can be relaxed by configuration;
 *       it is the trust model working.</li>
 *   <li>{@link EffectClass#NETWORK} — a claim about what an unknown tool might do. It cannot be
 *       verified, so it is set high rather than trusted from the server's own description.</li>
 * </ul>
 *
 * <p>Ids are namespaced {@code mcp.<server>.<tool>}, which both prevents collision with
 * {@code builtin.*} and makes provenance visible in every audit line. A server cannot masquerade
 * as a built-in by naming a tool {@code read_file}.
 */
public final class McpCapabilityHandler implements CapabilityHandler {

    private final McpClient client;
    private final String toolName;
    private final CapabilityDescriptor descriptor;

    public McpCapabilityHandler(McpClient client, McpClient.McpTool tool) {
        this(client, tool, TrustClass.COMMUNITY, EffectClass.NETWORK);
    }

    /**
     * @param trust  the installation's trust: {@code VERIFIED} for a package signed by a trusted
     *               publisher, else {@code COMMUNITY}
     * @param effect the effect class the tools carry; a verified manifest's declaration, else
     *               {@code NETWORK}. The caller decides that, never the server
     */
    public McpCapabilityHandler(McpClient client, McpClient.McpTool tool, TrustClass trust, EffectClass effect) {
        this.client = Objects.requireNonNull(client, "client");
        this.toolName = Objects.requireNonNull(tool, "tool").name();
        this.descriptor = new CapabilityDescriptor(
                CapabilityId.of(idFor(client.serverName(), tool.name())),
                describe(client.serverName(), tool),
                tool.inputSchema(),
                Objects.requireNonNull(effect, "effect"),
                Objects.requireNonNull(trust, "trust"));
    }

    /** Namespaced id, sanitized to the {@code lower_snake} segments {@link CapabilityId} allows. */
    static String idFor(String serverName, String toolName) {
        return "mcp." + sanitize(serverName) + "." + sanitize(toolName);
    }

    private static String sanitize(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        // Segments must start with a letter.
        return cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0)) ? "s" + cleaned : cleaned;
    }

    /**
     * Description shown to the model.
     *
     * <p>Prefixed with the server name deliberately. The rest is text the server author wrote, and
     * it goes into the prompt — labelling its origin means a model reading an instruction-like
     * description at least sees where it came from.
     */
    private static String describe(String serverName, McpClient.McpTool tool) {
        String body = tool.description().isBlank() ? tool.name() : tool.description();
        return "[via MCP server '" + serverName + "'] " + body;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
        if (!client.isAlive()) {
            return Result.err(HandlerError.failed("mcp_server_not_running"));
        }
        return client.callTool(toolName, invocation.arguments())
                .mapErr(HandlerError::failed);
    }

    /** Registers every tool a connected server offers. */
    public static Result<List<CapabilityHandler>, String> handlersFor(McpClient client) {
        return handlersFor(client, TrustClass.COMMUNITY, EffectClass.NETWORK);
    }

    /** Registers every tool a connected server offers, at the given trust and effect. */
    public static Result<List<CapabilityHandler>, String> handlersFor(
            McpClient client, TrustClass trust, EffectClass effect) {
        Result<List<CapabilityHandler>, String> tools = client.offersOrUnknown("tools")
                ? client.listTools().map(discovered -> discovered.stream()
                        .map(tool -> (CapabilityHandler) new McpCapabilityHandler(client, tool, trust, effect))
                        .toList())
                : Result.ok(List.of());
        // Resources and prompts are additive: a server that offers them gets a few more
        // capabilities, and one that does not is never asked.
        return tools.map(discovered -> {
            List<CapabilityHandler> all = new java.util.ArrayList<>(discovered);
            all.addAll(McpSurfaceTools.handlersFor(client, trust, effect));
            return List.copyOf(all);
        });
    }
}
