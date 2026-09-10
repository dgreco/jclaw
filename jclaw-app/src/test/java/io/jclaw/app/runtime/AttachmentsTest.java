// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("an image becomes a base64 image block with its media type")
    void image() throws IOException {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};
        Path file = dir.resolve("shot.PNG");
        Files.write(file, png);

        ContentBlock block = ((Result.Ok<ContentBlock, String>) Attachments.load(file)).value();
        ContentBlock.Image image = assertInstanceOf(ContentBlock.Image.class, block);
        assertEquals("image/png", image.mediaType());
        assertEquals(Base64.getEncoder().encodeToString(png), image.data());
    }

    @Test
    @DisplayName("a text file is quoted under its name")
    void text() throws IOException {
        Path file = dir.resolve("notes.md");
        Files.writeString(file, "# Notes\nline");

        ContentBlock block = ((Result.Ok<ContentBlock, String>) Attachments.load(file)).value();
        ContentBlock.Text text = assertInstanceOf(ContentBlock.Text.class, block);
        assertTrue(text.text().startsWith("Attached file `notes.md`:\n```\n# Notes\nline\n```"));
    }

    @Test
    @DisplayName("binary that is neither image nor UTF-8 is refused with a reason")
    void unsupported() throws IOException {
        Path file = dir.resolve("blob.bin");
        Files.write(file, new byte[]{(byte) 0xff, (byte) 0xfe, 0, (byte) 0xc3});

        Result<ContentBlock, String> result = Attachments.load(file);
        assertTrue(result.isErr());
        assertTrue(((Result.Err<ContentBlock, String>) result).error().contains("unsupported"));
        assertTrue(Attachments.load(dir.resolve("missing.txt")).isErr());
    }

    @Test
    @DisplayName("the user message carries the prompt first, then attachments in order")
    void userMessage() throws IOException {
        Path a = dir.resolve("a.txt");
        Files.writeString(a, "A");
        Path b = dir.resolve("b.png");
        Files.write(b, new byte[]{1});

        ChatMessage message = ((Result.Ok<ChatMessage, String>) Attachments.userMessage("look", List.of(a, b))).value();
        assertEquals(ChatMessage.Role.USER, message.role());
        assertEquals(3, message.content().size());
        assertEquals("look", ((ContentBlock.Text) message.content().get(0)).text());
        assertInstanceOf(ContentBlock.Text.class, message.content().get(1));
        assertInstanceOf(ContentBlock.Image.class, message.content().get(2));
        assertTrue(message.weight() > 6000, "an image weighs like ~1600 tokens for the context estimate");
    }
}
