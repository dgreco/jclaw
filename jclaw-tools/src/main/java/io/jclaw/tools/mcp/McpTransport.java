package io.jclaw.tools.mcp;

import io.jclaw.contracts.Result;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * How JSON-RPC envelopes reach an MCP server: a child process over stdio, or HTTP.
 *
 * <p>Extracted from the client so the protocol is written once. Everything above this interface —
 * the handshake, tool discovery, tool calls, resources, prompts — is identical whichever transport
 * carries it, which is the point: a server's transport is deployment, not capability.
 *
 * <p>Errors are stable tokens rather than the server's own words. A remote server's error text is
 * third-party content, and it would otherwise reach the model through a failure message.
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Sends one envelope.
     *
     * @param expectId the JSON-RPC id to wait for, or empty for a notification (no reply)
     * @return the {@code result} object of the matching response, empty map when it has none
     */
    Result<Map<String, Object>, String> send(
            Map<String, Object> envelope, Optional<Long> expectId, Duration timeout);

    /** Whether the server is still reachable. */
    boolean isAlive();

    /** A short description for logs and {@code mcp list}. Never includes a credential. */
    String describe();

    @Override
    void close();
}
