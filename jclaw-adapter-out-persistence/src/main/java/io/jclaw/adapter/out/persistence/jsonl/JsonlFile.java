// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.jsonl;

import io.jclaw.adapter.out.persistence.rows.RowStore;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Append-only JSON Lines file: one JSON object per line.
 *
 * <p>Chosen over a database for the event and transcript logs because the access pattern is
 * append-and-replay, and JSONL is the format that survives everything — you can tail it, grep it,
 * and recover it with a text editor when something has gone badly wrong. A corrupt line costs you
 * that line, not the file.
 *
 * <p>Deliberately serializes {@code Map<String, Object>} rather than typed objects. Hand-built maps
 * mean no reflection (which GraalVM native image would otherwise need configuration for), and no
 * possibility of a field added to a record silently appearing in a log that was reviewed as safe.
 *
 * <p>Writes are synchronized and flushed per line. That is slower than batching and it is the right
 * trade: an event log that loses its tail on a crash cannot answer the question it exists to answer.
 */
public final class JsonlFile implements RowStore {

    private final Path path;
    private final JsonMapper mapper;
    private final Object writeLock = new Object();

    public JsonlFile(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath();
        this.mapper = JsonMapper.builder().build();
        try {
            Path parent = this.path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(this.path)) {
                Files.createFile(this.path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialize JSONL file at " + this.path, e);
        }
    }

    public Path path() {
        return path;
    }

    /**
     * Appends one record.
     *
     * <p>Serialization happens before the file is opened, so a value that cannot be encoded fails
     * without leaving a truncated line behind.
     */
    @Override
    public void append(Map<String, Object> record) {
        Objects.requireNonNull(record, "record");
        String line = mapper.writeValueAsString(record);
        if (line.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("serialized record contains a newline; would corrupt JSONL");
        }
        synchronized (writeLock) {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot append to " + path, e);
            }
        }
    }

    /**
     * Reads every record in order.
     *
     * <p>Skips lines that fail to parse rather than aborting: a partially written final line after
     * a crash must not make the entire history unreadable.
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<Map<String, Object>> readAll() {
        List<Map<String, Object>> records = new ArrayList<>();
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    records.add(mapper.readValue(line, Map.class));
                } catch (RuntimeException e) {
                    // Jackson 3 exceptions are unchecked. A damaged line is skipped by design.
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        return records;
    }

    /**
     * Replaces the whole file with {@code records}, atomically.
     *
     * <p>The one deliberate departure from append-only, used by retention. The new content is
     * written to a sibling temp file and moved over the original, so a reader never sees a
     * half-written file: it sees the old one or the new one.
     */
    @Override
    public void rewrite(List<Map<String, Object>> records) {
        Objects.requireNonNull(records, "records");
        StringBuilder content = new StringBuilder();
        for (Map<String, Object> record : records) {
            String line = mapper.writeValueAsString(record);
            if (line.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("serialized record contains a newline; would corrupt JSONL");
            }
            content.append(line).append('\n');
        }
        synchronized (writeLock) {
            Path temp = path.resolveSibling(path.getFileName() + ".rewrite");
            try {
                Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot rewrite " + path, e);
            }
        }
    }

    /** Number of well-formed records currently stored. */
    @Override
    public int size() {
        return readAll().size();
    }

    /** Removes all content. Intended for tests and explicit user-initiated resets. */
    public void truncate() {
        synchronized (writeLock) {
            try {
                Files.writeString(path, "", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot truncate " + path, e);
            }
        }
    }
}
