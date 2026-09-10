// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Turns operator-supplied files into content blocks for a user message.
 *
 * <p>Two extractors, deliberately minimal. An image becomes an {@link ContentBlock.Image} block,
 * base64 inline, which is what every provider that accepts images accepts. A text file becomes a
 * {@link ContentBlock.Text} block quoting its contents under its name, so the model can cite it
 * and the transcript stays greppable. Anything else is refused with a reason rather than sent as
 * bytes the model cannot read: a PDF or a spreadsheet needs a real extractor, and pretending
 * otherwise would produce a confident answer about content the model never saw.
 *
 * <p>Attachments are the operator's own files, read as the operator, so the workspace guard does
 * not apply here; it constrains the agent's tools, not the human's input. Size is bounded so one
 * attachment cannot exhaust a context window or a transcript file.
 */
public final class Attachments {

    /** Largest image accepted, in bytes; providers reject much larger ones anyway. */
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** Largest text file inlined. */
    static final long MAX_TEXT_BYTES = 256L * 1024;

    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    private Attachments() {
    }

    /** Loads one file into a block, or a stable reason it could not be attached. */
    public static Result<ContentBlock, String> load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            return Result.err("not a file: " + file.getFileName());
        }
        String name = String.valueOf(file.getFileName());
        String extension = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        try {
            long size = Files.size(file);
            String imageType = IMAGE_TYPES.get(extension);
            if (imageType != null) {
                if (size > MAX_IMAGE_BYTES) {
                    return Result.err("image too large (" + size + " bytes, limit " + MAX_IMAGE_BYTES + "): " + name);
                }
                return Result.ok(new ContentBlock.Image(
                        imageType, Base64.getEncoder().encodeToString(Files.readAllBytes(file))));
            }
            if (size > MAX_TEXT_BYTES) {
                return Result.err("text file too large (" + size + " bytes, limit " + MAX_TEXT_BYTES + "): " + name);
            }
            byte[] bytes = Files.readAllBytes(file);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException e) {
                return Result.err("unsupported attachment type (not an image, not UTF-8 text): " + name);
            }
            return Result.ok(new ContentBlock.Text(
                    "Attached file `" + name + "`:\n```\n" + text + (text.endsWith("\n") ? "" : "\n") + "```"));
        } catch (IOException e) {
            return Result.err("cannot read attachment: " + name);
        }
    }

    /**
     * Builds the user message for a prompt plus attachments: prose first, then each attachment
     * in the order given, so the model reads the request before the material.
     */
    public static Result<ChatMessage, String> userMessage(String prompt, List<Path> files) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(files, "files");
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new ContentBlock.Text(prompt));
        for (Path file : files) {
            Result<ContentBlock, String> loaded = load(file);
            if (loaded instanceof Result.Err<ContentBlock, String> err) {
                return Result.err(err.error());
            }
            blocks.add(((Result.Ok<ContentBlock, String>) loaded).value());
        }
        return Result.ok(new ChatMessage(ChatMessage.Role.USER, blocks));
    }
}
