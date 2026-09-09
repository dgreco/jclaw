package io.jclaw.providers.mock;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.model.ModelExchange.StopReason;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scripted provider that replays a fixed sequence of responses.
 *
 * <p>This is what makes the harness testable end to end without a network, an API key, or a
 * nondeterministic model. A golden test scripts exactly what the "model" says — including tool
 * calls and failures — and asserts on what the loop does about it.
 *
 * <p>It is a first-class part of the design rather than a test fixture bolted on afterwards. Being
 * able to reproduce an agent run exactly is the difference between debugging a loop and guessing
 * at it.
 */
public final class MockModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(MockModelProvider.class);

    /** One scripted turn. */
    public sealed interface Script {

        /** Reply with plain text and end the turn. */
        record Text(String text) implements Script {
            public Text {
                Objects.requireNonNull(text, "text");
            }
        }

        /** Request a tool call. */
        record ToolCall(String callId, String tool, Map<String, Object> arguments) implements Script {
            public ToolCall {
                Objects.requireNonNull(callId, "callId");
                Objects.requireNonNull(tool, "tool");
                arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
            }
        }

        /** Fail this exchange. */
        record Failure(ProviderFailure failure) implements Script {
            public Failure {
                Objects.requireNonNull(failure, "failure");
            }
        }
    }

    private final Deque<Script> remaining;
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile ModelRequest lastRequest;
    private final String modelId;

    /** When true the final scripted turn repeats instead of the script running dry. */
    private final boolean repeatLast;

    private Script lastServed;

    public MockModelProvider(List<Script> script) {
        this(script, "mock-model", false);
    }

    public MockModelProvider(List<Script> script, String modelId) {
        this(script, modelId, false);
    }

    public MockModelProvider(List<Script> script, String modelId, boolean repeatLast) {
        this.remaining = new ArrayDeque<>(Objects.requireNonNull(script, "script"));
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.repeatLast = repeatLast;
    }

    /**
     * A provider that always replies with the same text, however many times it is called.
     *
     * <p>Implemented by replaying a single scripted turn rather than by subclassing, so the class
     * stays final and every instance goes through the same code path.
     */
    public static MockModelProvider alwaysReplying(String text) {
        return new MockModelProvider(List.of(new Script.Text(text)), "mock-model", true);
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public boolean supports(String model) {
        return true; // the mock stands in for anything
    }

    @Override
    public Result<ModelResponse, ProviderFailure> complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        callCount.incrementAndGet();
        lastRequest = request;

        Script next = remaining.poll();
        if (next == null && repeatLast) {
            next = lastServed;
        }
        if (next != null) {
            lastServed = next;
        }
        if (next == null) {
            // Running off the end of a script is a test bug, and a silent empty reply would hide
            // it. Fail with a category the loop can surface.
            log.debug("mock: script exhausted on call {}", callCount.get());
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.UPSTREAM, "mock script exhausted"));
        }
        log.debug("mock: call {} serving scripted {} ({} step(s) remaining)",
                callCount.get(),
                switch (next) {
                    case Script.Text ignored -> "text reply";
                    case Script.ToolCall call -> "tool call " + call.tool();
                    case Script.Failure failure -> "failure " + failure.failure().kind();
                },
                remaining.size());

        return switch (next) {
            case Script.Text text -> Result.ok(new ModelResponse(
                    ChatMessage.assistant(text.text()), StopReason.END_TURN, Usage.of(10, 5), modelId));

            case Script.ToolCall call -> Result.ok(new ModelResponse(
                    new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                            new ContentBlock.ToolUse(call.callId(), call.tool(), call.arguments()))),
                    StopReason.TOOL_USE, Usage.of(10, 5), modelId));

            case Script.Failure failure -> Result.err(failure.failure());
        };
    }

    /**
     * Replaces the remaining script and resets the call counter.
     *
     * <p>Exists so each test supplies its own turns rather than sharing one script across a class,
     * where JUnit's unspecified method order would silently give tests each other's responses.
     */
    public void reprogram(List<Script> script) {
        Objects.requireNonNull(script, "script");
        remaining.clear();
        remaining.addAll(script);
        lastServed = null;
        callCount.set(0);
    }

    /** How many times the provider has been called. Lets a test assert on retry behaviour. */
    /** The most recent request, so a test can assert what the loop actually sent. */
    public java.util.Optional<ModelRequest> lastRequest() {
        return java.util.Optional.ofNullable(lastRequest);
    }

    public int callCount() {
        return callCount.get();
    }

    /** Scripted turns not yet consumed. */
    public int remainingScript() {
        return remaining.size();
    }
}
