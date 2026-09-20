// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.thread;

import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ContentBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCodecTest {

    @Test
    @DisplayName("text, tool use, tool result, and image blocks round-trip; thinking is dropped")
    void roundTrip() {
        ChatMessage message = new ChatMessage(ChatMessage.Role.USER, List.of(
                new ContentBlock.Text("look at this"),
                new ContentBlock.Image("image/png", "iVBORw0KGgo="),
                new ContentBlock.ToolUse("c1", "builtin.read_file", Map.of("path", "x")),
                new ContentBlock.ToolResult("c1", "contents", false),
                new ContentBlock.Thinking("private", "sig")));

        ChatMessage decoded = MessageCodec.decode(MessageCodec.encode(message));

        assertEquals(4, decoded.content().size(), "reasoning is never persisted");
        assertEquals(message.content().subList(0, 4), decoded.content());
        ContentBlock.Image image = (ContentBlock.Image) decoded.content().get(1);
        assertEquals("image/png", image.mediaType());
        assertTrue(decoded.displayText().equals("look at this"), "images are not display text");
    }
}
