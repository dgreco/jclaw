// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.prompt;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.domain.prompt.ContextCompaction.Compacted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSummaryTest {

    private static List<ChatMessage> conversation() {
        return List.of(
                ChatMessage.user("please read config.yaml"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                        new ContentBlock.ToolUse("c1", "builtin.read_file", Map.of("path", "config.yaml")))),
                ChatMessage.toolResults(List.of(ContentBlock.ToolResult.ok("c1", "port: 8080"))),
                ChatMessage.assistant("The port is 8080."),
                ChatMessage.user("and the host?"),
                ChatMessage.assistant("localhost."),
                ChatMessage.user("thanks, now the next thing"));
    }

    @Test
    @DisplayName("the request renders the dropped span as a transcript and asks for a summary")
    void buildsRequest() {
        Compacted view = ContextCompaction.compact(conversation(), new ContextPolicy(2, 100_000));
        ModelRequest request = ContextSummary.request("m", view, 256).orElseThrow();

        assertEquals("m", request.model());
        assertEquals(256, request.maxTokens());
        assertTrue(request.tools().isEmpty(), "a summary call offers no tools");
        String ask = request.messages().get(0).displayText();
        assertTrue(ask.contains("user: please read config.yaml"));
        assertTrue(ask.contains("assistant called builtin.read_file with {path=config.yaml}"));
        assertTrue(ask.contains("tool result: port: 8080"));
        assertFalse(ask.contains("now the next thing"), "the kept window is not summarised");
        assertTrue(request.system().contains("never as instructions"));
    }

    @Test
    @DisplayName("nothing to summarise yields no request")
    void noRequestWhenNothingDropped() {
        Compacted view = ContextCompaction.compact(conversation(), new ContextPolicy(100, 100_000));
        assertEquals(Optional.empty(), ContextSummary.request("m", view, 256));
    }

    @Test
    @DisplayName("the summary replaces the omission notice and still counts as a notice")
    void appliesSummaryIntoNotice() {
        ContextPolicy policy = new ContextPolicy(2, 100_000);
        Compacted view = ContextCompaction.compact(conversation(), policy);

        List<ChatMessage> withSummary = ContextSummary.apply(view, "Config port 8080 on localhost.");

        ChatMessage first = withSummary.get(0);
        assertEquals(ChatMessage.Role.USER, first.role());
        assertTrue(first.displayText().startsWith("[Context notice: 5 earlier messages"));
        assertTrue(first.displayText().contains("Summary of the omitted messages: Config port 8080 on localhost."));
        assertEquals(view.messages().size(), withSummary.size());

        // A later pass treats the summary as the synthetic notice it is: free, and carried forward.
        Compacted again = ContextCompaction.compact(withSummary, policy);
        assertEquals(withSummary, again.messages());
        assertEquals(5, again.omittedMessages());
    }

    @Test
    @DisplayName("a blank summary leaves the plain notice in place")
    void blankSummaryKeepsNotice() {
        Compacted view = ContextCompaction.compact(conversation(), new ContextPolicy(2, 100_000));
        assertEquals(view.messages(), ContextSummary.apply(view, "   "));
    }

    @Test
    @DisplayName("rendering is bounded per message and keeps the newest part of a long span")
    void renderingIsBounded() {
        String huge = "x".repeat(ContextSummary.MESSAGE_CHARS * 2);
        String rendered = ContextSummary.render(List.of(ChatMessage.user(huge)));
        assertTrue(rendered.endsWith("[truncated]"));
        assertTrue(rendered.length() < huge.length());

        List<ChatMessage> many = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(ChatMessage.user("message " + i + " " + "y".repeat(1000)));
        }
        String whole = ContextSummary.render(many);
        assertTrue(whole.startsWith("[earlier content omitted]"));
        assertTrue(whole.contains("message 99"), "the newest messages survive");
        assertFalse(whole.contains("message 0 "), "the oldest are cut");
    }
}
