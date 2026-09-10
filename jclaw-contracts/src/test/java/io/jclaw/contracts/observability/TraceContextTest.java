// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @Test
    @DisplayName("the header is the W3C form, and round-trips")
    void headerFormat() {
        TraceContext sampled = new TraceContext(TRACE, SPAN, true);
        assertEquals("00-" + TRACE + "-" + SPAN + "-01", sampled.traceparent());
        assertEquals("00-" + TRACE + "-" + SPAN + "-00",
                new TraceContext(TRACE, SPAN, false).traceparent());
        assertEquals(Optional.of(sampled), TraceContext.parse(sampled.traceparent()));
        assertEquals(Optional.of(sampled), TraceContext.parse("  " + sampled.traceparent() + " "));
    }

    @Test
    @DisplayName("ids must be the right shape, and anything else is refused rather than sent")
    void idsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new TraceContext("short", SPAN, true));
        assertThrows(IllegalArgumentException.class, () -> new TraceContext(TRACE, "short", true));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceContext(TRACE.toUpperCase(java.util.Locale.ROOT), SPAN, true),
                "the specification says lower-case hex");
    }

    @Test
    @DisplayName("an unparseable header is empty, never an exception")
    void parsingIsTotal() {
        assertTrue(TraceContext.parse(null).isEmpty());
        assertTrue(TraceContext.parse("").isEmpty());
        assertTrue(TraceContext.parse("garbage").isEmpty());
        assertTrue(TraceContext.parse("01-" + TRACE + "-" + SPAN + "-01").isEmpty(),
                "a future version is not something to guess at");
        assertTrue(TraceContext.parse("00-" + TRACE + "-" + SPAN).isEmpty());
        assertTrue(TraceContext.parse("00-nothex" + TRACE.substring(6) + "-" + SPAN + "-01").isEmpty());
    }

    @Test
    @DisplayName("a scope restores what was current, and nesting works")
    void scopesNestAndRestore() {
        assertTrue(TraceContext.current().isEmpty(), "no scope, no context");

        TraceContext outer = new TraceContext(TRACE, SPAN, true);
        TraceContext inner = new TraceContext(TRACE, "1111111111111111", true);
        try (var ignored = TraceContext.open(outer)) {
            assertEquals(Optional.of(outer), TraceContext.current());
            try (var alsoIgnored = TraceContext.open(inner)) {
                assertEquals(Optional.of(inner), TraceContext.current());
            }
            assertEquals(Optional.of(outer), TraceContext.current(),
                    "closing the inner scope must not strand the thread without the outer one");
        }
        assertTrue(TraceContext.current().isEmpty(), "and the thread is left clean");
    }

    @Test
    @DisplayName("a scope closes even when the work throws")
    void scopeClosesOnFailure() {
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = TraceContext.open(new TraceContext(TRACE, SPAN, true))) {
                throw new IllegalStateException("boom");
            }
        });
        assertTrue(TraceContext.current().isEmpty(),
                "a failed call must not leave the next one attributed to it");
    }

    @Test
    @DisplayName("another thread does not inherit the context")
    void notInherited() throws Exception {
        AtomicReference<Optional<TraceContext>> seen = new AtomicReference<>();
        try (var ignored = TraceContext.open(new TraceContext(TRACE, SPAN, true));
             var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> seen.set(TraceContext.current())).get();
        }
        assertTrue(seen.get().isEmpty(),
                "a subagent runs on its own thread and is its own trace, not a continuation");
    }
}
