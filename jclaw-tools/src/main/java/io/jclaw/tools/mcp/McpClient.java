package io.jclaw.tools.mcp;

import io.jclaw.contracts.Result;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal Model Context Protocol client over stdio.
 *
 * <p>MCP is JSON-RPC 2.0 with a small handshake. This implements the part an agent harness
 * actually needs — {@code initialize}, {@code tools/list}, {@code tools/call} — over the stdio
 * transport, which is what local MCP servers use. HTTP/SSE transports are not implemented.
 *
 * <p>Written directly rather than pulled from a library because the surface is three methods and
 * the process lifecycle is the hard part, not the protocol.
 *
 * <h2>Security posture</h2>
 *
 * <p>An MCP server is <b>third-party code the user chose to install</b>, and its tool descriptions
 * are attacker-influenced text that ends up in the model's prompt. Two consequences are enforced
 * outside this class and worth stating here:
 *
 * <ul>
 *   <li>MCP capabilities are registered as {@link io.jclaw.contracts.capability.TrustClass#COMMUNITY},
 *       whose auto-approval ceiling is {@code PURE}. Every MCP tool call therefore requires human
 *       approval, whatever the operator's policy says — policy may tighten a third-party trust
 *       ceiling, never raise it.</li>
 *   <li>The child process inherits a scrubbed environment, so an MCP server cannot read the
 *       harness's credentials out of its own environment.</li>
 * </ul>
 */
public final class McpClient implements AutoCloseable {

    /** MCP revision this client speaks. */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Environment a server process may inherit. Same discipline as {@link ShellTool}: without it,
     * an installed MCP server can read {@code ANTHROPIC_API_KEY} straight out of its environment.
     */
    private static final Set<String> ENV_ALLOWLIST =
            Set.of("PATH", "HOME", "LANG", "LC_ALL", "TZ", "TERM", "USER", "TMPDIR");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicLong nextId = new AtomicLong(1);

    private final String serverName;
    private final Process process;
    private final BufferedWriter toServer;
    private final BufferedReader fromServer;

    private McpClient(String serverName, Process process) {
        this.serverName = serverName;
        this.process = process;
        this.toServer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromServer = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** One tool advertised by a server. */
    public record McpTool(String name, String description, Map<String, Object> inputSchema) {
        public McpTool {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
        }
    }

    /**
     * Starts a server and completes the MCP handshake.
     *
     * @param workingDirectory directory the server runs in; the workspace root, so a filesystem
     *                         MCP server is confined the same way the built-in tools are
     */
    public static Result<McpClient, String> start(
            String serverName, List<String> command, Map<String, String> extraEnv, Path workingDirectory) {

        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            return Result.err("empty_command");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        // stderr stays separate: server diagnostics must never be parsed as protocol.
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> !ENV_ALLOWLIST.contains(key));
        environment.putAll(extraEnv);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return Result.err("server_spawn_failed");
        }

        McpClient client = new McpClient(serverName, process);
        Result<Map<String, Object>, String> handshake = client.request("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "jclaw", "version", "0.1.0")));

        if (handshake.isErr()) {
            client.close();
            return Result.err(handshake.errorAsOptional().orElse("handshake_failed"));
        }
        // MCP requires this notification before any other call.
        client.notification("notifications/initialized", Map.of());
        return Result.ok(client);
    }

    /** Lists the tools this server offers. */
    @SuppressWarnings("unchecked")
    public Result<List<McpTool>, String> listTools() {
        return request("tools/list", Map.of()).flatMap(result -> {
            Object rawTools = result.get("tools");
            if (!(rawTools instanceof List<?> list)) {
                return Result.err("malformed_tools_list");
            }
            List<McpTool> tools = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> tool = (Map<String, Object>) map;
                    tools.add(new McpTool(
                            String.valueOf(tool.get("name")),
                            String.valueOf(tool.getOrDefault("description", "")),
                            tool.get("inputSchema") instanceof Map<?, ?> schema
                                    ? (Map<String, Object>) schema
                                    : Map.of()));
                }
            }
            return Result.ok(List.copyOf(tools));
        });
    }

    /**
     * Invokes a tool and flattens its content to text.
     *
     * <p>MCP results are a list of typed content blocks; only text is extracted. Binary content is
     * summarized rather than inlined, because a base64 image in a tool result would consume the
     * context window to no purpose.
     */
    public Result<String, String> callTool(String name, Map<String, Object> arguments) {
        return request("tools/call", Map.of("name", name, "arguments", arguments))
                .flatMap(McpClient::flattenContent);
    }

    @SuppressWarnings("unchecked")
    private static Result<String, String> flattenContent(Map<String, Object> result) {
        if (result.get("isError") instanceof Boolean error && error) {
            return Result.err("tool_reported_error");
        }
        if (!(result.get("content") instanceof List<?> blocks)) {
            return Result.ok("(no content)");
        }
        StringBuilder text = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map) {
                Map<String, Object> typed = (Map<String, Object>) map;
                String kind = String.valueOf(typed.get("type"));
                if ("text".equals(kind)) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(String.valueOf(typed.get("text")));
                } else {
                    text.append("\n[").append(kind).append(" content omitted]");
                }
            }
        }
        return Result.ok(text.isEmpty() ? "(no text content)" : text.toString());
    }

    public String serverName() {
        return serverName;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    // --- JSON-RPC plumbing ---

    /** Sends a request and waits for the matching response. */
    @SuppressWarnings("unchecked")
    private Result<Map<String, Object>, String> request(String method, Map<String, Object> params) {
        long id = nextId.getAndIncrement();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.put("params", params);

        try {
            send(envelope);
        } catch (IOException | RuntimeException e) {
            return Result.err("server_write_failed");
        }

        long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String line;
            try {
                line = fromServer.readLine();
            } catch (IOException e) {
                return Result.err("server_read_failed");
            }
            if (line == null) {
                return Result.err("server_closed_stream");
            }
            if (line.isBlank()) {
                continue;
            }

            Map<String, Object> message;
            try {
                message = mapper.readValue(line, Map.class);
            } catch (RuntimeException e) {
                continue; // not protocol; ignore rather than abort
            }

            // Skip notifications and responses to other requests.
            Object responseId = message.get("id");
            if (!(responseId instanceof Number number) || number.longValue() != id) {
                continue;
            }
            if (message.get("error") instanceof Map<?, ?> error) {
                // The server's error message is third-party text; only its code is used.
                Object code = ((Map<String, Object>) error).get("code");
                return Result.err("server_error_" + code);
            }
            return message.get("result") instanceof Map<?, ?> result
                    ? Result.ok((Map<String, Object>) result)
                    : Result.ok(Map.of());
        }
        return Result.err("server_timeout");
    }

    /** Fire-and-forget message; MCP notifications carry no id and expect no reply. */
    private void notification(String method, Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("method", method);
        envelope.put("params", params);
        try {
            send(envelope);
        } catch (IOException | RuntimeException e) {
            // Best effort by definition: there is no reply to miss.
        }
    }

    private void send(Map<String, Object> envelope) throws IOException {
        toServer.write(mapper.writeValueAsString(envelope));
        toServer.newLine();
        toServer.flush();
    }

    @Override
    public void close() {
        // Destroy the whole tree: an MCP server that spawned helpers would otherwise leak them.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
