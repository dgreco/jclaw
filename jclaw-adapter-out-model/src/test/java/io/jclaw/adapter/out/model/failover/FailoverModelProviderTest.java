// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.model.failover;

import io.jclaw.ports.Result;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ModelExchange.ModelRequest;
import io.jclaw.ports.model.ModelExchange.ModelResponse;
import io.jclaw.ports.model.ModelExchange.StopReason;
import io.jclaw.ports.model.ModelExchange.Usage;
import io.jclaw.ports.model.ModelProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failover chain's rules, which decide when a second provider is asked for the same answer.
 *
 * <p>Every case here is a rule the class documents, and each one costs something real when it
 * breaks: failing over a malformed request multiplies one rejection into several, retrying a dead
 * provider adds its whole timeout to every turn, and failing over mid-stream shows the user the
 * start of two different answers.
 */
class FailoverModelProviderTest {

    private static final ModelRequest REQUEST =
            ModelRequest.of("some-model", "system", List.of(ChatMessage.user("hello")), 128);

    /** A clock the test moves by hand, so cooldown expiry needs no sleeping. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T12:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    /** A provider that answers however the test says, and records that it was asked. */
    private static final class FakeProvider implements ModelProvider {
        private final String id;
        private final boolean supports;
        private final Result<ModelResponse, ProviderFailure> answer;
        private final List<StreamEvent> emitBeforeAnswering;
        private int calls;

        FakeProvider(String id, Result<ModelResponse, ProviderFailure> answer) {
            this(id, true, answer, List.of());
        }

        FakeProvider(String id, boolean supports, Result<ModelResponse, ProviderFailure> answer,
                     List<StreamEvent> emitBeforeAnswering) {
            this.id = id;
            this.supports = supports;
            this.answer = answer;
            this.emitBeforeAnswering = emitBeforeAnswering;
        }

        @Override public String id() { return id; }
        @Override public boolean supports(String model) { return supports; }

        @Override
        public Result<ModelResponse, ProviderFailure> complete(ModelRequest request) {
            calls++;
            return answer;
        }

        @Override
        public Result<ModelResponse, ProviderFailure> stream(ModelRequest request, Consumer<StreamEvent> sink) {
            calls++;
            emitBeforeAnswering.forEach(sink);
            return answer;
        }
    }

    private static Result<ModelResponse, ModelProvider.ProviderFailure> reply(String text) {
        return Result.ok(new ModelResponse(
                ChatMessage.assistant(text), StopReason.END_TURN, Usage.of(1, 1), "some-model"));
    }

    private static Result<ModelResponse, ModelProvider.ProviderFailure> failure(
            ModelProvider.ProviderFailure.Kind kind) {
        return Result.err(ModelProvider.ProviderFailure.of(kind, "test"));
    }

    private static String textOf(Result<ModelResponse, ModelProvider.ProviderFailure> result) {
        return result.orElseThrow().message().displayText();
    }

    @Test
    @DisplayName("an empty chain is refused at construction, not at the first request")
    void emptyChainRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new FailoverModelProvider(List.of(), Clock.systemUTC()));
    }

    @Test
    @DisplayName("the first provider that answers wins, and the rest are never asked")
    void firstSuccessWins() {
        FakeProvider first = new FakeProvider("first", reply("from first"));
        FakeProvider second = new FakeProvider("second", reply("from second"));
        var failover = new FailoverModelProvider(List.of(first, second), new TestClock());

        assertEquals("from first", textOf(failover.complete(REQUEST)));
        assertEquals(1, first.calls);
        assertEquals(0, second.calls, "a provider after a success must not be asked");
    }

    @Test
    @DisplayName("a provider that does not support the model is skipped without being called")
    void unsupportedModelSkipped() {
        FakeProvider wrongModel = new FakeProvider("wrong", false, reply("never"), List.of());
        FakeProvider right = new FakeProvider("right", reply("from right"));
        var failover = new FailoverModelProvider(List.of(wrongModel, right), new TestClock());

        assertEquals("from right", textOf(failover.complete(REQUEST)));
        assertEquals(0, wrongModel.calls, "supports() is asked before the model is");
    }

    @Test
    @DisplayName("no provider accepts the model: one failure naming it, and nobody is called")
    void noProviderForModel() {
        FakeProvider only = new FakeProvider("only", false, reply("never"), List.of());
        var failover = new FailoverModelProvider(List.of(only), new TestClock());

        var failed = failover.complete(REQUEST).errorAsOptional().orElseThrow();
        assertEquals(ModelProvider.ProviderFailure.Kind.UPSTREAM, failed.kind());
        assertTrue(failed.detail().orElse("").contains("some-model"),
                "the detail should name the model nothing could serve: " + failed.detail());
        assertEquals(0, only.calls);
    }

    @Nested
    @DisplayName("only a retryable failure advances the chain")
    class Advancing {

        @Test
        @DisplayName("a rate limit moves to the next provider")
        void retryableAdvances() {
            FakeProvider limited = new FakeProvider("limited",
                    failure(ModelProvider.ProviderFailure.Kind.RATE_LIMIT));
            FakeProvider healthy = new FakeProvider("healthy", reply("from healthy"));
            var failover = new FailoverModelProvider(List.of(limited, healthy), new TestClock());

            assertEquals("from healthy", textOf(failover.complete(REQUEST)));
            assertEquals(1, limited.calls);
            assertEquals(1, healthy.calls);
        }

        @Test
        @DisplayName("a malformed request stops the chain: every provider would reject it alike")
        void nonRetryableStops() {
            FakeProvider rejecting = new FakeProvider("rejecting",
                    failure(ModelProvider.ProviderFailure.Kind.INVALID_REQUEST));
            FakeProvider healthy = new FakeProvider("healthy", reply("from healthy"));
            var failover = new FailoverModelProvider(List.of(rejecting, healthy), new TestClock());

            var failed = failover.complete(REQUEST).errorAsOptional().orElseThrow();
            assertEquals(ModelProvider.ProviderFailure.Kind.INVALID_REQUEST, failed.kind());
            assertEquals(0, healthy.calls,
                    "failing over a rejected request turns one bad request into several");
        }

        @Test
        @DisplayName("a missing credential stops the chain too, and is reported as itself")
        void authStops() {
            FakeProvider noKey = new FakeProvider("nokey",
                    failure(ModelProvider.ProviderFailure.Kind.AUTH));
            FakeProvider healthy = new FakeProvider("healthy", reply("from healthy"));
            var failover = new FailoverModelProvider(List.of(noKey, healthy), new TestClock());

            assertEquals(ModelProvider.ProviderFailure.Kind.AUTH,
                    failover.complete(REQUEST).errorAsOptional().orElseThrow().kind());
            assertEquals(0, healthy.calls);
        }

        @Test
        @DisplayName("when every provider fails retryably, the last failure is what comes back")
        void allFailing() {
            FakeProvider first = new FakeProvider("first",
                    failure(ModelProvider.ProviderFailure.Kind.RATE_LIMIT));
            FakeProvider second = new FakeProvider("second",
                    failure(ModelProvider.ProviderFailure.Kind.TRANSPORT));
            var failover = new FailoverModelProvider(List.of(first, second), new TestClock());

            assertEquals(ModelProvider.ProviderFailure.Kind.TRANSPORT,
                    failover.complete(REQUEST).errorAsOptional().orElseThrow().kind());
        }
    }

    @Nested
    @DisplayName("a failed provider cools down")
    class Cooldown {

        @Test
        @DisplayName("it is skipped entirely while cooling, and asked again once the clock passes")
        void skippedThenRetried() {
            FakeProvider flaky = new FakeProvider("flaky",
                    failure(ModelProvider.ProviderFailure.Kind.UPSTREAM));
            FakeProvider healthy = new FakeProvider("healthy", reply("from healthy"));
            TestClock clock = new TestClock();
            var failover = new FailoverModelProvider(
                    List.of(flaky, healthy), Duration.ofSeconds(60), clock);

            failover.complete(REQUEST);
            assertEquals(1, flaky.calls);
            assertEquals(List.of("flaky"), failover.providersInCooldown());

            failover.complete(REQUEST);
            assertEquals(1, flaky.calls, "a provider in cooldown must not be asked at all");

            clock.advance(Duration.ofSeconds(61));
            assertTrue(failover.providersInCooldown().isEmpty(), "the cooldown lapses on the clock");
            failover.complete(REQUEST);
            assertEquals(2, flaky.calls, "once the cooldown lapses it is tried again");
        }

        @Test
        @DisplayName("a provider that recovers is taken out of cooldown by its own success")
        void successClearsCooldown() {
            var answer = new Object() {
                Result<ModelResponse, ModelProvider.ProviderFailure> value =
                        failure(ModelProvider.ProviderFailure.Kind.UPSTREAM);
            };
            ModelProvider recovering = new ModelProvider() {
                @Override public String id() { return "recovering"; }
                @Override public boolean supports(String model) { return true; }
                @Override public Result<ModelResponse, ProviderFailure> complete(ModelRequest request) {
                    return answer.value;
                }
            };
            TestClock clock = new TestClock();
            var failover = new FailoverModelProvider(
                    List.of(recovering, new FakeProvider("spare", reply("spare"))),
                    Duration.ofSeconds(60), clock);

            failover.complete(REQUEST);
            assertEquals(List.of("recovering"), failover.providersInCooldown());

            answer.value = reply("recovered");
            clock.advance(Duration.ofSeconds(61));
            assertEquals("recovered", textOf(failover.complete(REQUEST)));
            assertTrue(failover.providersInCooldown().isEmpty(),
                    "a success clears the cooldown rather than letting it merely expire again");
        }
    }

    @Nested
    @DisplayName("streaming does not start a second answer on top of the first")
    class Streaming {

        @Test
        @DisplayName("a failure after prose has been shown is final, however retryable it is")
        void noFailoverAfterOutput() {
            FakeProvider talkative = new FakeProvider("talkative", true,
                    failure(ModelProvider.ProviderFailure.Kind.TRANSPORT),
                    List.of(new ModelProvider.StreamEvent.TextDelta("half an answer")));
            FakeProvider healthy = new FakeProvider("healthy", reply("a whole other answer"));
            var failover = new FailoverModelProvider(List.of(talkative, healthy), new TestClock());

            List<String> seen = new ArrayList<>();
            var result = failover.stream(REQUEST, event -> {
                if (event instanceof ModelProvider.StreamEvent.TextDelta delta) {
                    seen.add(delta.text());
                }
            });

            assertTrue(result.isErr(), "the failure is returned as is");
            assertEquals(0, healthy.calls,
                    "the user has seen the start of one answer; a second provider would begin another");
            assertEquals(List.of("half an answer"), seen);
        }

        @Test
        @DisplayName("a tool call already announced counts as output, the same as prose")
        void toolUseAlsoCountsAsOutput() {
            FakeProvider announced = new FakeProvider("announced", true,
                    failure(ModelProvider.ProviderFailure.Kind.UPSTREAM),
                    List.of(new ModelProvider.StreamEvent.ToolUseStarted("call-1", "builtin.read_file")));
            FakeProvider healthy = new FakeProvider("healthy", reply("other"));
            var failover = new FailoverModelProvider(List.of(announced, healthy), new TestClock());

            assertTrue(failover.stream(REQUEST, event -> { }).isErr());
            assertEquals(0, healthy.calls, "a started tool call is output too");
        }

        @Test
        @DisplayName("a failure before anything is shown still fails over")
        void failoverBeforeAnyOutput() {
            FakeProvider silent = new FakeProvider("silent", true,
                    failure(ModelProvider.ProviderFailure.Kind.RATE_LIMIT), List.of());
            FakeProvider healthy = new FakeProvider("healthy", reply("from healthy"));
            var failover = new FailoverModelProvider(List.of(silent, healthy), new TestClock());

            assertEquals("from healthy", textOf(failover.stream(REQUEST, event -> { })));
            assertEquals(1, healthy.calls);
        }
    }

    @Test
    @DisplayName("supports() is true when any provider in the chain accepts the model")
    void supportsIsTheUnion() {
        var failover = new FailoverModelProvider(List.of(
                new FakeProvider("no", false, reply("x"), List.of()),
                new FakeProvider("yes", true, reply("x"), List.of())), new TestClock());
        assertTrue(failover.supports("some-model"));

        var noneSupport = new FailoverModelProvider(
                List.of(new FakeProvider("no", false, reply("x"), List.of())), new TestClock());
        assertFalse(noneSupport.supports("some-model"));
        assertEquals("failover", failover.id());
    }
}
