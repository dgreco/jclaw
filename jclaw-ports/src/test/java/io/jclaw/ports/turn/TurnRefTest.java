// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.turn;

import io.jclaw.ports.turn.TurnRef.AcceptedMessageRef;
import io.jclaw.ports.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.ports.turn.TurnRef.LoopGateRef;
import io.jclaw.ports.turn.TurnRef.LoopMessageRef;
import io.jclaw.ports.turn.TurnRef.LoopResultRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ref vocabulary a driver must hand back instead of a claim.
 *
 * <p>These records cannot make a ref trustworthy — validation here is a format check, and what
 * makes a ref evidence is that the store it names resolves it. What they can do is refuse a value
 * that could never name a record, and keep the five kinds distinct so that a result ref cannot be
 * passed where a gate ref belongs. Both are pinned here, because both are load-bearing in
 * {@code JclawRuntime.validate} and neither is obvious from the type alone.
 */
class TurnRefTest {

    /** Every ref kind, as a constructor taking the raw value. */
    private static Stream<Function<String, TurnRef>> constructors() {
        return Stream.of(AcceptedMessageRef::new, LoopMessageRef::new, LoopResultRef::new,
                LoopGateRef::new, LoopCheckpointStateRef::new);
    }

    @Test
    @DisplayName("a blank ref is refused: nothing durable is named by an empty string")
    void blankRefused() {
        constructors().forEach(make -> {
            assertThrows(IllegalArgumentException.class, () -> make.apply(""));
            assertThrows(IllegalArgumentException.class, () -> make.apply("   "));
            assertThrows(NullPointerException.class, () -> make.apply(null));
        });
    }

    @Test
    @DisplayName("whitespace inside a ref is refused, not trimmed away")
    void internalWhitespaceRefused() {
        constructors().forEach(make ->
                assertThrows(IllegalArgumentException.class, () -> make.apply("msg_one msg_two"),
                        "a value with a space in it would name two things or neither"));
    }

    @Test
    @DisplayName("surrounding whitespace is normalised, so equality does not turn on it")
    void surroundingWhitespaceTrimmed() {
        assertEquals(new LoopResultRef("res_1"), new LoopResultRef("  res_1  "));
    }

    @Test
    @DisplayName("an over-long value is refused rather than silently truncated")
    void lengthBounded() {
        String tooLong = "res_" + "x".repeat(200);
        assertThrows(IllegalArgumentException.class, () -> new LoopResultRef(tooLong));
    }

    @Test
    @DisplayName("each kind is minted from its own id type, and stays that kind")
    void mintedFromTheirOwnIds() {
        assertEquals("msg_1", AcceptedMessageRef.of(new MessageId("msg_1")).value());
        assertEquals("msg_2", LoopMessageRef.of(new MessageId("msg_2")).value());
        assertEquals("gate_1", LoopGateRef.of(new GateId("gate_1")).value());
        assertEquals("ckpt_1", LoopCheckpointStateRef.of(new CheckpointId("ckpt_1")).value());
        assertInstanceOf(LoopGateRef.class, LoopGateRef.of(new GateId("gate_1")));
    }

    @Test
    @DisplayName("every kind reports a stable name, because audit lines are keyed on it")
    void kindNamesAreStable() {
        assertEquals("accepted_message", new AcceptedMessageRef("msg_1").kind());
        assertEquals("loop_message", new LoopMessageRef("msg_1").kind());
        assertEquals("loop_result", new LoopResultRef("res_1").kind());
        assertEquals("loop_gate", new LoopGateRef("gate_1").kind());
        assertEquals("loop_checkpoint", new LoopCheckpointStateRef("ckpt_1").kind());

        // Five kinds, five names, none shared: an audit line that said "loop_message" for two
        // different kinds of evidence would be unreadable after the fact.
        assertEquals(5, Stream.of(
                        new AcceptedMessageRef("a").kind(), new LoopMessageRef("a").kind(),
                        new LoopResultRef("a").kind(), new LoopGateRef("a").kind(),
                        new LoopCheckpointStateRef("a").kind())
                .distinct().count());
    }

    @Test
    @DisplayName("require refuses a null ref, naming what was missing")
    void requireRefusesNull() {
        LoopResultRef present = new LoopResultRef("res_1");
        assertEquals(present, TurnRef.require(present, "result"));

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> TurnRef.require((LoopResultRef) null, "result ref"));
        assertEquals("result ref", thrown.getMessage());
    }

    @Test
    @DisplayName("two refs of different kinds are never equal, even holding the same value")
    void kindsDoNotCompareEqual() {
        assertEquals(new LoopResultRef("x_1"), new LoopResultRef("x_1"));
        assertNotEquals(new LoopResultRef("x_1"), (Object) new LoopGateRef("x_1"));
    }
}
