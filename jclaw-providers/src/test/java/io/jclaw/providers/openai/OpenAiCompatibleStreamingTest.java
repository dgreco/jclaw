package io.jclaw.providers.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jclaw.contracts.Result;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.model.ModelExchange.StopReason;
import io.jclaw.contracts.model.ModelExchange.ToolSpec;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.model.ModelProvider.ProviderFailure;
import io.jclaw.contracts.model.ModelProvider.StreamEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SSE path against a real loopback server that speaks the chat-completions stream format:
 * prose deltas, a tool call whose arguments arrive in fragments, a usage trailer, and
 * {@code [DONE]}.
 */
class OpenAiCompatibleStreamingTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAccept = new AtomicReference<>();
    private final AtomicReference<List<String>> events = new AtomicReference<>(List.of());
    private volatile int status = 200;

    /** One SSE event per element; the server adds the blank-line terminators. */
    private static final List<String> HAPPY_PATH = List.of(
            "data: {\"id\":\"c1\",\"model\":\"qwen2.5\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"c1\",\"model\":\"qwen2.5\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"c1\",\"model\":\"qwen2.5\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"builtin_read_file\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
            "data: {\"id\":\"c1\",\"model\":\"qwen2.5\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\"}}]},\"finish_reason\":null}]}",
            "data: {\"id\":\"c1\",\"model\":\"qwen2.5\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"README.md\\\"}\"}}]},\"finish_reason\":null}]}",
            "data: {\"id\":\"c1\",\"model\":\"qwen2.5\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "data: {\"id\":\"c1\",\"model\":\"qwen2.5\",\"choices\":[],\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":9}}",
            "data: [DONE]");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        events.set(HAPPY_PATH);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
        try (InputStream in = exchange.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        if (status != 200) {
            byte[] body = "{\"error\":{\"message\":\"model not found\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0); // chunked
        try (OutputStream out = exchange.getResponseBody()) {
            for (String event : events.get()) {
                out.write((event + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
        exchange.close();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ModelProvider provider() {
        return new OpenAiCompatibleModelProvider("local", baseUrl, Optional.empty());
    }

    private static ModelRequest request() {
        return ModelRequest.of("qwen2.5", "be brief", List.of(ChatMessage.user("hello")), 64)
                .withTools(List.of(new ToolSpec("builtin.read_file", "Read a file",
                        Map.of("type", "object"))));
    }

    @Test
    @DisplayName("prose deltas reach the sink as they arrive and the assembled reply matches the buffered shape")
    void streamsAndAssembles() {
        List<StreamEvent> seen = new ArrayList<>();
        Result<ModelResponse, ProviderFailure> result = provider().stream(request(), seen::add);

        assertTrue(result.isOk(), "expected success, got " + result);
        ModelResponse response = ((Result.Ok<ModelResponse, ProviderFailure>) result).value();

        // What the terminal saw: two prose chunks, one tool-call start, one completion.
        assertEquals(List.of("Hel", "lo"), seen.stream()
                .filter(StreamEvent.TextDelta.class::isInstance)
                .map(e -> ((StreamEvent.TextDelta) e).text()).toList());
        StreamEvent.ToolUseStarted started = seen.stream()
                .filter(StreamEvent.ToolUseStarted.class::isInstance)
                .map(StreamEvent.ToolUseStarted.class::cast).findFirst().orElseThrow();
        assertEquals("call_9", started.callId());
        assertEquals("builtin.read_file", started.name(), "wire names are decoded for the sink too");
        assertInstanceOf(StreamEvent.Completed.class, seen.get(seen.size() - 1));

        // What the machine gets: the same response the buffered path would build.
        assertEquals("Hello", response.text());
        assertEquals(StopReason.TOOL_USE, response.stopReason());
        assertEquals("qwen2.5", response.modelId());
        assertEquals(21, response.usage().inputTokens());
        assertEquals(9, response.usage().outputTokens());
        ContentBlock.ToolUse use = response.toolUses().get(0);
        assertEquals("call_9", use.callId());
        assertEquals("builtin.read_file", use.name(), "the dotted capability id is restored");
        assertEquals(Map.of("path", "README.md"), use.input(),
                "argument fragments are concatenated and parsed once");
    }

    @Test
    @DisplayName("the request asks for a stream with usage, and for event-stream content")
    void requestShape() {
        provider().stream(request(), event -> { });

        assertTrue(lastBody.get().contains("\"stream\":true"));
        assertTrue(lastBody.get().contains("\"include_usage\":true"));
        assertEquals("text/event-stream", lastAccept.get());
    }

    @Test
    @DisplayName("an HTTP error is classified and no Completed event is emitted")
    void errorIsClassified() {
        status = 404;
        List<StreamEvent> seen = new ArrayList<>();

        Result<ModelResponse, ProviderFailure> result = provider().stream(request(), seen::add);

        ProviderFailure failure = ((Result.Err<ModelResponse, ProviderFailure>) result).error();
        assertEquals(ProviderFailure.Kind.UNKNOWN_MODEL, failure.kind());
        assertTrue(seen.isEmpty(), "nothing should reach the sink on failure");
    }

    @Test
    @DisplayName("a stream that ends without [DONE] still assembles what arrived")
    void toleratesMissingDone() {
        events.set(List.of(
                "data: {\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}"));

        Result<ModelResponse, ProviderFailure> result = provider().stream(request(), event -> { });

        ModelResponse response = ((Result.Ok<ModelResponse, ProviderFailure>) result).value();
        assertEquals("partial", response.text());
        assertEquals(StopReason.END_TURN, response.stopReason());
    }
}
