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

    /**
     * Answers a request the <em>server</em> initiated, such as {@code sampling/createMessage}.
     *
     * <p>MCP is bidirectional: a server may ask the client to do something, and the only one of
     * those jclaw answers is sampling. A handler returns the JSON-RPC {@code result} object, or
     * an error to send back.
     *
     * <p>Unset by default, and a transport with no handler simply skips such a frame — which is
     * the correct behaviour for a client that never advertised the capability. A server sending
     * one anyway is not a reason to fail the call in flight.
     */
    @FunctionalInterface
    interface ServerRequests {
        /**
         * @param method the JSON-RPC method the server asked for
         * @param params its params, decoded
         * @return the result object, or an error token to return as a JSON-RPC error
         */
        Result<Map<String, Object>, String> answer(String method, Map<String, Object> params);
    }

    /** Installs the handler for server-initiated requests. Set once, before any call. */
    default void onServerRequest(ServerRequests handler) {
        // Most transports never see one; a default keeps them from having to say so.
    }

    /** Whether the server is still reachable. */
    boolean isAlive();

    /** A short description for logs and {@code mcp list}. Never includes a credential. */
    String describe();

    @Override
    void close();
}
