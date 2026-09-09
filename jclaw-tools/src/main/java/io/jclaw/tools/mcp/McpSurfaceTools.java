package io.jclaw.tools.mcp;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.HandlerError;
import io.jclaw.contracts.capability.TrustClass;
import io.jclaw.tools.Schemas;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The rest of an MCP server's surface: its resources and its prompts.
 *
 * <p>A server's tools are actions; its <em>resources</em> are addressable content it can read
 * (a file, a record, a page) and its <em>prompts</em> are templates it offers. Both reach the
 * model as ordinary capabilities, so they cross the same authority boundary as everything else,
 * with the same trust class: content fetched from a third-party server is exactly the kind of
 * thing the injection heuristics exist for.
 *
 * <p>Registered only when the server declared the capability in its handshake. Asking a server
 * for resources it never claimed to have produces an error the model would have to read.
 */
public final class McpSurfaceTools {

    private McpSurfaceTools() {
    }

    /** Handlers for whichever of resources and prompts {@code client} declared. */
    public static List<CapabilityHandler> handlersFor(McpClient client, TrustClass trust, EffectClass effect) {
        Objects.requireNonNull(client, "client");
        List<CapabilityHandler> handlers = new java.util.ArrayList<>();
        if (client.offers().contains("resources")) {
            handlers.add(new ListResources(client, trust, effect));
            handlers.add(new ReadResource(client, trust, effect));
        }
        if (client.offers().contains("prompts")) {
            handlers.add(new ListPrompts(client, trust, effect));
            handlers.add(new GetPrompt(client, trust, effect));
        }
        return List.copyOf(handlers);
    }

    private static CapabilityDescriptor describeCapability(
            McpClient client, String verb, String description, Map<String, Object> schema,
            TrustClass trust, EffectClass effect) {
        return new CapabilityDescriptor(
                CapabilityId.of(McpCapabilityHandler.idFor(client.serverName(), verb)),
                "[via MCP server '" + client.serverName() + "'] " + description,
                schema, effect, trust);
    }

    private static Result<String, HandlerError> requireAlive(McpClient client) {
        return client.isAlive() ? null : Result.err(HandlerError.failed("mcp_server_not_running"));
    }

    /** Lists what the server can read, so the model can choose a URI before asking for one. */
    static final class ListResources implements CapabilityHandler {

        private final McpClient client;
        private final CapabilityDescriptor descriptor;

        ListResources(McpClient client, TrustClass trust, EffectClass effect) {
            this.client = client;
            this.descriptor = describeCapability(client, "list_resources",
                    "List the resources this server can read: uri, name, and description.",
                    Schemas.noArguments(), trust, effect);
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            Result<String, HandlerError> dead = requireAlive(client);
            if (dead != null) {
                return dead;
            }
            return client.listResources().<Result<String, HandlerError>>fold(
                    resources -> Result.ok(resources.isEmpty() ? "(no resources)" : resources.stream()
                            .map(r -> "- " + r.uri() + "  " + r.name()
                                    + (r.description().isBlank() ? "" : ": " + r.description())
                                    + " [" + r.mimeType() + "]")
                            .collect(Collectors.joining("\n"))),
                    reason -> Result.err(HandlerError.failed(reason)));
        }
    }

    /** Reads one resource by URI. */
    static final class ReadResource implements CapabilityHandler {

        private final McpClient client;
        private final CapabilityDescriptor descriptor;

        ReadResource(McpClient client, TrustClass trust, EffectClass effect) {
            this.client = client;
            this.descriptor = describeCapability(client, "read_resource",
                    "Read one resource this server offers, by its uri.",
                    Schemas.object(
                            Schemas.properties("uri", Schemas.string("Resource uri, as listed by list_resources.")),
                            List.of("uri")),
                    trust, effect);
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            Result<String, HandlerError> dead = requireAlive(client);
            if (dead != null) {
                return dead;
            }
            String uri = invocation.stringArg("uri", "");
            if (uri.isBlank()) {
                return Result.err(HandlerError.failed("uri_required"));
            }
            return client.readResource(uri).mapErr(HandlerError::failed);
        }
    }

    /** Lists the prompt templates the server offers. */
    static final class ListPrompts implements CapabilityHandler {

        private final McpClient client;
        private final CapabilityDescriptor descriptor;

        ListPrompts(McpClient client, TrustClass trust, EffectClass effect) {
            this.client = client;
            this.descriptor = describeCapability(client, "list_prompts",
                    "List the prompt templates this server offers and the arguments each takes.",
                    Schemas.noArguments(), trust, effect);
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            Result<String, HandlerError> dead = requireAlive(client);
            if (dead != null) {
                return dead;
            }
            return client.listPrompts().<Result<String, HandlerError>>fold(
                    prompts -> Result.ok(prompts.isEmpty() ? "(no prompts)" : prompts.stream()
                            .map(p -> "- " + p.name()
                                    + (p.arguments().isEmpty() ? "" : "(" + String.join(", ", p.arguments()) + ")")
                                    + (p.description().isBlank() ? "" : ": " + p.description()))
                            .collect(Collectors.joining("\n"))),
                    reason -> Result.err(HandlerError.failed(reason)));
        }
    }

    /** Expands one prompt template. */
    static final class GetPrompt implements CapabilityHandler {

        private final McpClient client;
        private final CapabilityDescriptor descriptor;

        GetPrompt(McpClient client, TrustClass trust, EffectClass effect) {
            this.client = client;
            this.descriptor = describeCapability(client, "get_prompt",
                    "Expand one of this server's prompt templates to its text.",
                    Schemas.object(
                            Schemas.properties(
                                    "name", Schemas.string("Prompt name, as listed by list_prompts."),
                                    "arguments", Map.of(
                                            "type", "object",
                                            "description", "Values for the template's arguments.",
                                            "additionalProperties", Map.of("type", "string"))),
                            List.of("name")),
                    trust, effect);
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return descriptor;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            Result<String, HandlerError> dead = requireAlive(client);
            if (dead != null) {
                return dead;
            }
            String name = invocation.stringArg("name", "");
            if (name.isBlank()) {
                return Result.err(HandlerError.failed("name_required"));
            }
            Map<String, Object> arguments = invocation.arguments().get("arguments") instanceof Map<?, ?> given
                    ? (Map<String, Object>) given
                    : Map.of();
            return client.getPrompt(name, arguments).mapErr(HandlerError::failed);
        }
    }
}
