// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.prompt;

import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ContentBlock;
import io.jclaw.domain.prompt.ContextCompaction.Compacted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactionTest {

    private static final ContextPolicy ROOMY = new ContextPolicy(100, 100_000);

    private static ChatMessage user(String text) {
        return ChatMessage.user(text);
    }

    private static ChatMessage assistant(String text) {
        return ChatMessage.assistant(text);
    }

    private static ChatMessage toolUse(String callId) {
        return new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                new ContentBlock.ToolUse(callId, "builtin.read_file", Map.of("path", "x"))));
    }

    private static ChatMessage toolResult(String callId, String content) {
        return ChatMessage.toolResults(List.of(ContentBlock.ToolResult.ok(callId, content)));
    }

    /** A five-turn conversation with one tool round in the middle. */
    private static List<ChatMessage> conversation() {
        return List.of(
                user("u0"),                       // 0
                assistant("a0"),                  // 1
                user("u1"),                       // 2
                toolUse("c1"),                    // 3
                toolResult("c1", "file body"),    // 4
                assistant("a1"),                  // 5
                user("u2"));                      // 6
    }

    @Test
    @DisplayName("a conversation within the policy passes through untouched")
    void unchangedWhenItFits() {
        List<ChatMessage> history = conversation();
        Compacted result = ContextCompaction.compact(history, ROOMY);

        assertEquals(history, result.messages());
        assertEquals(0, result.omittedMessages());
        assertFalse(result.compacted());
    }

    @Test
    @DisplayName("the message cap drops the oldest messages and cuts at a user boundary")
    void messageCapCutsAtUserBoundary() {
        // The newest five start at u1, which is a user message: kept as is, notice folded in.
        Compacted result = ContextCompaction.compact(conversation(), new ContextPolicy(5, 100_000));

        assertEquals(2, result.omittedMessages());
        assertEquals(5, result.messages().size(), "no synthetic turn when the cut is a user message");
        assertEquals(List.of(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT, ChatMessage.Role.TOOL,
                        ChatMessage.Role.ASSISTANT, ChatMessage.Role.USER),
                result.messages().stream().map(ChatMessage::role).toList());
        assertTrue(result.messages().get(0).displayText().endsWith("u1"));
    }

    @Test
    @DisplayName("the kept window never opens with a tool result")
    void neverStartsWithToolResult() {
        // The newest three are [tool result, a1, u2]. A tool result cannot open a conversation,
        // so the cut moves forward to a1 and a synthetic user notice is placed in front of it.
        Compacted result = ContextCompaction.compact(conversation(), new ContextPolicy(3, 100_000));

        assertNotEquals(ChatMessage.Role.TOOL, result.messages().get(0).role());
        assertEquals(List.of(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT, ChatMessage.Role.USER),
                result.messages().stream().map(ChatMessage::role).toList());
        assertEquals(5, result.omittedMessages());
    }

    @Test
    @DisplayName("a tool call is never separated from its results")
    void toolRoundStaysTogether() {
        // Budget that admits the tool round but not the turns before it.
        List<ChatMessage> history = conversation();
        int roundAndAfter = ContextCompaction.estimateTokens(history.subList(3, 7));
        Compacted result = ContextCompaction.compact(history, new ContextPolicy(100, roundAndAfter + 1));

        List<ChatMessage> kept = result.messages();
        assertEquals(3, result.omittedMessages());
        // Synthetic user notice, then the assistant's tool call, then its result.
        assertEquals(ChatMessage.Role.USER, kept.get(0).role());
        assertTrue(kept.get(0).displayText().contains("3 earlier messages"));
        assertTrue(kept.get(1).hasToolUses(), "the tool call opens the real history");
        assertEquals(ChatMessage.Role.TOOL, kept.get(2).role(), "its result follows immediately");
    }

    @Test
    @DisplayName("the notice is folded into the first kept user message, not added as a turn")
    void noticeFoldedIntoUserMessage() {
        Compacted result = ContextCompaction.compact(conversation(), new ContextPolicy(1, 100_000));

        assertEquals(1, result.messages().size(), "no extra turn is inserted");
        ChatMessage first = result.messages().get(0);
        assertEquals(ChatMessage.Role.USER, first.role());
        assertTrue(first.displayText().startsWith("[Context notice: 6 earlier messages"));
        assertTrue(first.displayText().endsWith("u2"), "the user's own text is preserved after the notice");
    }

    @Test
    @DisplayName("the current message is kept even when it alone exceeds the budget")
    void currentMessageAlwaysKept() {
        List<ChatMessage> history = new ArrayList<>(conversation());
        history.add(user("x".repeat(4000)));

        Compacted result = ContextCompaction.compact(history, new ContextPolicy(100, 10));

        assertEquals(1, result.messages().size());
        assertTrue(result.messages().get(0).displayText().endsWith("x".repeat(4000)));
        assertEquals(history.size() - 1, result.omittedMessages());
    }

    @Test
    @DisplayName("the token budget binds when the message cap does not")
    void tokenBudgetBinds() {
        List<ChatMessage> history = List.of(
                user("a".repeat(400)),
                assistant("b".repeat(400)),
                user("c".repeat(400)));
        int lastTwo = ContextCompaction.estimateTokens(history.subList(1, 3));

        Compacted result = ContextCompaction.compact(history, new ContextPolicy(100, lastTwo));

        assertEquals(1, result.omittedMessages());
        assertEquals(ChatMessage.Role.USER, result.messages().get(0).role(),
                "the assistant boundary gets a synthetic user notice in front");
        assertTrue(result.messages().get(0).displayText().contains("1 earlier message in this conversation was omitted"));
    }

    @Test
    @DisplayName("compacting an already compacted view changes nothing")
    void idempotent() {
        ContextPolicy policy = new ContextPolicy(2, 100_000);
        Compacted once = ContextCompaction.compact(conversation(), policy);
        Compacted twice = ContextCompaction.compact(once.messages(), policy);

        assertEquals(once.messages(), twice.messages(),
                "the synthetic notice must not count against the cap or be stacked");
        assertEquals(once.omittedMessages(), twice.omittedMessages(),
                "a pass that drops nothing still reports the running total");
    }

    @Test
    @DisplayName("a later pass that drops the notice carries its count forward")
    void carriesOmittedCountForward() {
        ContextPolicy wide = new ContextPolicy(4, 100_000);
        ContextPolicy narrow = new ContextPolicy(2, 100_000);
        // Seed pass: keeps [notice, toolUse, result, a1, u2], reports 3 omitted.
        Compacted seed = ContextCompaction.compact(conversation(), wide);
        assertEquals(3, seed.omittedMessages());

        // The run then grows by a tool round and the machine compacts under a tighter cap.
        List<ChatMessage> grown = new ArrayList<>(seed.messages());
        grown.add(toolUse("c2"));
        grown.add(toolResult("c2", "more"));
        Compacted later = ContextCompaction.compact(grown, narrow);

        assertEquals(3 + 4, later.omittedMessages(),
                "3 from the seed pass plus the 4 real messages dropped now");
        assertTrue(later.messages().get(0).displayText().startsWith("[Context notice: 7 earlier"));
        assertEquals(3, later.messages().size(), "notice + tool call + result");
    }

    @Test
    @DisplayName("compaction is deterministic")
    void deterministic() {
        ContextPolicy policy = new ContextPolicy(3, 1000);
        assertEquals(
                ContextCompaction.compact(conversation(), policy),
                ContextCompaction.compact(conversation(), policy));
    }

    @Test
    @DisplayName("empty history compacts to nothing")
    void emptyHistory() {
        Compacted result = ContextCompaction.compact(List.of(), ROOMY);
        assertTrue(result.messages().isEmpty());
        assertEquals(0, result.estimatedTokens());
    }

    @Test
    @DisplayName("the estimate counts characters over four plus framing")
    void estimate() {
        assertEquals(ContextCompaction.MESSAGE_OVERHEAD_TOKENS + 25,
                ContextCompaction.estimateTokens(user("x".repeat(100))));
    }

    @Test
    @DisplayName("a policy rejects non-positive limits")
    void policyValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ContextPolicy(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ContextPolicy(10, 0));
        assertSame(ContextPolicy.DEFAULT, ContextPolicy.DEFAULT);
    }
}
