// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.model.mock;

import io.jclaw.ports.Result;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ModelExchange.ModelRequest;
import io.jclaw.ports.model.ModelExchange.ModelResponse;
import io.jclaw.ports.model.ModelProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockModelProviderTest {

    private static ModelRequest anyRequest() {
        return ModelRequest.of("mock-model", "system", List.of(ChatMessage.user("hello")), 128);
    }

    @Test
    @DisplayName("a script is served exactly once per entry, even when several runs call at once")
    void servesEachTurnOnceUnderConcurrency() throws InterruptedException {
        // The mock is not only a test double: `serve --concurrency N` executes several runs
        // against the one provider bean, which is precisely what a webhook topic fan-out
        // produces. A non-thread-safe queue here hands two runs the same turn or loses one, and
        // the loss surfaces as "mock script exhausted" in a run that did nothing wrong.
        int turns = 400;
        int threads = 8;
        MockModelProvider provider = new MockModelProvider(IntStream.range(0, turns)
                .mapToObj(i -> (MockModelProvider.Script) new MockModelProvider.Script.Text("turn-" + i))
                .toList());

        Set<String> served = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < turns / threads; i++) {
                        Result<ModelResponse, ModelProvider.ProviderFailure> answer =
                                provider.complete(anyRequest());
                        answer.fold(
                                response -> served.add(response.text()) || duplicates.incrementAndGet() > 0,
                                failure -> failures.incrementAndGet() > 0);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "the pool should finish");
        }

        assertEquals(0, failures.get(), "no caller should see the script run dry: it had one turn each");
        assertEquals(0, duplicates.get(), "no turn should be served twice");
        assertEquals(turns, served.size(), "every turn should have been served");
    }
}
