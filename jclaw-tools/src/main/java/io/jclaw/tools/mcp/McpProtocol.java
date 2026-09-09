package io.jclaw.tools.mcp;

import io.jclaw.contracts.Result;

import java.util.Map;

/** Shared JSON-RPC decoding, so both transports agree on what a response is. */
final class McpProtocol {

    private McpProtocol() {
    }

    /**
     * Interprets one decoded message against the id being awaited.
     *
     * @return the result (or a stable error token) when this message answers {@code id};
     *         {@code null} when it is a notification or another request's reply, which the
     *         caller should skip
     */
    @SuppressWarnings("unchecked")
    static Result<Map<String, Object>, String> matchResponse(Map<String, Object> message, long id) {
        Object responseId = message.get("id");
        if (!(responseId instanceof Number number) || number.longValue() != id) {
            return null;
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
}
