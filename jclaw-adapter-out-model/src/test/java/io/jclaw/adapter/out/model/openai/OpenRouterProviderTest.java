// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.model.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jclaw.ports.Result;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ContentBlock;
import io.jclaw.ports.model.ModelExchange.ModelRequest;
import io.jclaw.ports.model.ModelExchange.ModelResponse;
import io.jclaw.ports.model.ModelExchange.StopReason;
import io.jclaw.ports.model.ModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the OpenRouter adapter against a real HTTP server.
 *
 * <p>A loopback server rather than a mocked {@code HttpClient}: the things worth checking are the
 * bytes on the wire — the auth header, the attribution headers, and the request shape — and a
 * mocked client would only confirm that the code calls the methods the test expects it to call.
 *
 * <p>The fake <b>validates tool names the way OpenAI does</b>. An earlier, permissive version of
 * this fixture accepted anything and so happily passed while every real request was rejected with
 * a 400 for sending {@code builtin.read_file} as a function name. A fake that is more forgiving
 * than the real thing is worse than no fake at all.
 */
class OpenRouterProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Map<String, List<String>>> lastHeaders = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    /** A canned OpenAI-format reply, which is what OpenRouter returns. */
    private static final String CHAT_RESPONSE = """
            {"id":"gen-1","model":"anthropic/claude-sonnet-4.6",
             "choices":[{"message":{"role":"assistant","content":"Routed reply."},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":7}}
            """;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    /** What OpenAI-compatible APIs accept for a function name. */
    private static final Pattern LEGAL_TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private void handle(HttpExchange exchange) throws IOException {
        lastHeaders.set(Map.copyOf(exchange.getRequestHeaders()));
        try (InputStream in = exchange.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        String illegal = firstIllegalToolName(lastBody.get());
        byte[] body;
        int status;
        if (illegal != null) {
            body = ("{\"error\":{\"message\":\"Invalid 'tools[0].function.name': "
                    + illegal + "\",\"type\":\"invalid_request_error\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            status = 400;
        } else {
            body = CHAT_RESPONSE.getBytes(StandardCharsets.UTF_8);
            status = 200;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** Crude scan of the serialized body for a function name the real API would reject. */
    private static String firstIllegalToolName(String body) {
        java.util.regex.Matcher matcher =
                Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        while (matcher.find()) {
            String name = matcher.group(1);
            // Only tool names are constrained; skip the model id and other "name" fields.
            if (name.contains(".") && !LEGAL_TOOL_NAME.matcher(name).matches()) {
                return name;
            }
        }
        return null;
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static ModelRequest request(String model) {
        return ModelRequest.of(model, "be brief", List.of(ChatMessage.user("hello")), 64);
    }

    private String header(String name) {
        List<String> values = lastHeaders.get().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
        return values.isEmpty() ? null : values.get(0);
    }

    @Test
    @DisplayName("sends the bearer token and both attribution headers")
    void sendsAuthAndAttributionHeaders() {
        ModelProvider provider = OpenAiCompatibleModelProvider.openrouter(
                "or-test-key", baseUrl, "https://example.test/jclaw", "jclaw");

        Result<ModelResponse, ModelProvider.ProviderFailure> result =
                provider.complete(request("anthropic/claude-sonnet-4.6"));

        assertTrue(result.isOk(), "expected a successful exchange, got " + result);
        assertEquals("Bearer or-test-key", header("Authorization"));
        assertEquals("https://example.test/jclaw", header("HTTP-Referer"));
        assertEquals("jclaw", header("X-Title"));
    }

    @Test
    @DisplayName("omits attribution headers entirely when they are not configured")
    void omitsBlankAttributionHeaders() {
        // An empty header is worse than no header: it is still sent, and still attributed.
        ModelProvider provider =
                OpenAiCompatibleModelProvider.openrouter("k", baseUrl, "", "");

        provider.complete(request("openai/gpt-5.2"));

        assertNull(header("HTTP-Referer"), "a blank referer must not be sent as an empty header");
        assertNull(header("X-Title"), "a blank title must not be sent as an empty header");
    }

    @Test
    @DisplayName("sends the namespaced model id through unchanged")
    void sendsNamespacedModelId() {
        ModelProvider provider = OpenAiCompatibleModelProvider.openrouter("k", baseUrl, "", "");

        provider.complete(request("meta-llama/llama-3.3-70b-instruct"));

        assertTrue(lastBody.get().contains("meta-llama/llama-3.3-70b-instruct"),
                "the org/model id must reach the gateway verbatim, got: " + lastBody.get());
    }

    @Test
    @DisplayName("parses the OpenAI-format reply back into jclaw's vocabulary")
    void parsesResponse() {
        ModelProvider provider = OpenAiCompatibleModelProvider.openrouter("k", baseUrl, "", "");

        ModelResponse response = provider.complete(request("anthropic/claude-sonnet-4.6"))
                .toOptional()
                .orElseThrow();

        assertEquals("Routed reply.", response.text());
        assertEquals(StopReason.END_TURN, response.stopReason());
        assertEquals(11, response.usage().inputTokens());
        assertEquals(7, response.usage().outputTokens());
        // The model that actually served the request, which after routing may differ from the ask.
        assertEquals("anthropic/claude-sonnet-4.6", response.modelId());
    }

    @Test
    @DisplayName("only claims namespaced model ids, so failover never routes a bare id here")
    void supportsOnlyNamespacedIds() {
        ModelProvider provider = OpenAiCompatibleModelProvider.openrouter("k", baseUrl, "", "");

        assertTrue(provider.supports("anthropic/claude-sonnet-4.6"));
        assertTrue(provider.supports("openai/gpt-5.2"));
        assertTrue(provider.supports("meta-llama/llama-3.3-70b-instruct"));

        // A bare id would 404 at the gateway. Refusing it here produces a clear
        // "no provider available" instead of a confusing upstream error.
        assertFalse(provider.supports("claude-opus-5"));
        assertFalse(provider.supports("gpt-4o"));
        assertFalse(provider.supports("llama3.2"));
        // Degenerate forms are not namespaces.
        assertFalse(provider.supports("/leading"));
        assertFalse(provider.supports("trailing/"));
    }

    @Test
    @DisplayName("the plain OpenAI provider still accepts bare ids")
    void openAiProviderUnaffected() {
        // The stricter predicate must be OpenRouter-specific, not a change to every gateway.
        ModelProvider openai = new OpenAiCompatibleModelProvider("openai", baseUrl, Optional.of("k"));

        assertTrue(openai.supports("gpt-4o"));
        assertTrue(openai.supports("llama3.2"));
        assertFalse(openai.supports("claude-opus-5"), "bare Anthropic ids belong to the SDK adapter");
    }

    @Test
    @DisplayName("never attempts an h2c upgrade: plain-HTTP requests carry no Upgrade headers")
    void doesNotAttemptH2cUpgrade() {
        // Java's HttpClient defaults to HTTP/2, which on http:// URLs adds
        // 'Connection: Upgrade, HTTP2-Settings' + 'Upgrade: h2c' to every request. llhttp-based
        // servers (uvicorn --http httptools, i.e. vLLM and anything on uvicorn[standard]) pause
        // body parsing on any Upgrade header, so the app sees an EMPTY body and FastAPI answers
        // "{'type': 'missing', 'loc': ('body',)}". The adapter must pin HTTP/1.1.
        ModelProvider provider = OpenAiCompatibleModelProvider.local(baseUrl, null);

        Result<ModelResponse, ModelProvider.ProviderFailure> result =
                provider.complete(request("llama3.2"));

        assertTrue(result.isOk(), "expected a successful exchange, got " + result);
        assertNull(header("Upgrade"), "an h2c upgrade attempt breaks llhttp-based local servers");
        assertNull(header("HTTP2-Settings"), "HTTP2-Settings only travels with an upgrade attempt");
        String connection = header("Connection");
        assertTrue(connection == null || !connection.toLowerCase().contains("upgrade"),
                "Connection must not request an upgrade, got: " + connection);
    }

    @Test
    @DisplayName("the local provider needs no credentials: no key, no Authorization header")
    void localSendsNoAuthWithoutKey() {
        // Most local servers (LM Studio, llama.cpp, Ollama) listen unauthenticated. A missing key
        // must mean "send nothing", not an AUTH failure before the request leaves the process.
        ModelProvider provider = OpenAiCompatibleModelProvider.local(baseUrl, null);

        Result<ModelResponse, ModelProvider.ProviderFailure> result =
                provider.complete(request("qwen2.5-coder-7b-instruct"));

        assertTrue(result.isOk(), "expected a successful exchange without credentials, got " + result);
        assertNull(header("Authorization"), "no key configured must mean no Authorization header");
    }

    @Test
    @DisplayName("the local provider sends a bearer token when one is configured")
    void localSendsBearerWhenKeyPresent() {
        // vLLM with --api-key, or a server behind an authenticating proxy.
        ModelProvider provider = OpenAiCompatibleModelProvider.local(baseUrl, "vllm-test-key");

        provider.complete(request("qwen2.5-coder-7b-instruct"));

        assertEquals("Bearer vllm-test-key", header("Authorization"));
    }

    @Test
    @DisplayName("a blank local key is treated as absent, not sent as an empty bearer")
    void localBlankKeyIsUnauthenticated() {
        ModelProvider provider = OpenAiCompatibleModelProvider.local(baseUrl, "");

        Result<ModelResponse, ModelProvider.ProviderFailure> result =
                provider.complete(request("llama3.2"));

        assertTrue(result.isOk(), "expected a successful exchange, got " + result);
        assertNull(header("Authorization"), "a blank key must not become 'Bearer '");
    }

    @Test
    @DisplayName("the local provider claims any id a local server might host, except bare Anthropic ids")
    void localSupportsArbitraryModelNames() {
        ModelProvider provider = OpenAiCompatibleModelProvider.local(baseUrl, null);

        assertTrue(provider.supports("qwen2.5-coder-7b-instruct")); // LM Studio style
        assertTrue(provider.supports("llama3.2"));                  // Ollama style
        assertTrue(provider.supports("TheBloke/Mistral-7B-GGUF"));  // HF repo id, vLLM style
        // In a failover chain the Anthropic SDK adapter owns bare claude-* ids; a local server
        // claiming them would swallow the request and answer with a confusing 404.
        assertFalse(provider.supports("claude-opus-5"));
    }

    @Test
    @DisplayName("dotted capability ids are encoded to legal function names")
    void encodesToolNames() {
        ModelProvider provider = OpenAiCompatibleModelProvider.openrouter("k", baseUrl, "", "");

        Result<ModelResponse, ModelProvider.ProviderFailure> result =
                provider.complete(request("anthropic/claude-sonnet-4.6").withTools(List.of(
                        new io.jclaw.ports.model.ModelExchange.ToolSpec(
                                "builtin.read_file", "Read a file",
                                Map.of("type", "object", "properties", Map.of())),
                        new io.jclaw.ports.model.ModelExchange.ToolSpec(
                                "mcp.demo.reverse", "Reverse text",
                                Map.of("type", "object", "properties", Map.of())))));

        // The strict fake rejects dotted names, so a pass here proves the encoding happened.
        assertTrue(result.isOk(), "expected acceptance, got " + result);

        String body = lastBody.get();
        assertTrue(body.contains("builtin_read_file"), "dots should become underscores");
        assertTrue(body.contains("mcp_demo_reverse"), "nested namespaces encode too");
        assertFalse(body.contains("\"builtin.read_file\""), "no dotted name may reach the wire");
    }

    @Test
    @DisplayName("a rejected request reports the provider's explanation, not a bare category")
    void surfacesProviderErrorMessage() throws IOException {
        // A gateway that always rejects, so the message-extraction path is exercised directly.
        server.removeContext("/api/v1/chat/completions");
        server.createContext("/api/v1/chat/completions", exchange -> {
            byte[] body = ("{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens'\","
                    + "\"type\":\"invalid_request_error\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ModelProvider.ProviderFailure failure =
                OpenAiCompatibleModelProvider.openrouter("k", baseUrl, "", "")
                        .complete(request("anthropic/claude-sonnet-4.6"))
                        .errorAsOptional()
                        .orElseThrow();

        assertEquals(ModelProvider.ProviderFailure.Kind.INVALID_REQUEST, failure.kind());
        // Without this the user sees only "request rejected" and the actual cause needs a packet
        // capture to find.
        assertTrue(failure.detail().orElse("").contains("max_tokens"),
                "the provider's explanation should reach the caller, got: " + failure.detail());
    }

    @Test
    @DisplayName("a tool call naming the wire form decodes back to the capability id")
    void parsesToolCall() throws IOException {
        server.removeContext("/api/v1/chat/completions");
        server.createContext("/api/v1/chat/completions", exchange -> {
            String json = """
                    {"model":"anthropic/claude-sonnet-4.6","choices":[{"message":{"role":"assistant",
                     "content":null,"tool_calls":[{"id":"call_1","type":"function",
                     "function":{"name":"builtin_read_file","arguments":"{\\"path\\":\\"a.txt\\"}"}}]},
                     "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":4}}
                    """;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ModelProvider provider = OpenAiCompatibleModelProvider.openrouter("k", baseUrl, "", "");
        ModelResponse response = provider.complete(
                        request("anthropic/claude-sonnet-4.6").withTools(List.of(
                                new io.jclaw.ports.model.ModelExchange.ToolSpec(
                                        "builtin.read_file", "Read a file",
                                        Map.of("type", "object", "properties", Map.of())))))
                .toOptional()
                .orElseThrow();

        assertEquals(StopReason.TOOL_USE, response.stopReason());
        assertEquals(1, response.toolUses().size());
        ContentBlock.ToolUse call = response.toolUses().get(0);
        // The wire name must be decoded back, or the kernel would deny an unknown capability.
        assertEquals("builtin.read_file", call.name());
        assertEquals("call_1", call.callId());
        // Arguments arrive as a JSON string and must be parsed, never string-matched.
        assertEquals("a.txt", call.input().get("path"));
    }

}
