// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.thread;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.MessageId;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRef.AcceptedMessageRef;
import io.jclaw.contracts.turn.TurnRef.LoopMessageRef;
import io.jclaw.storage.rows.RowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable {@link ThreadService} over an append-only JSONL transcript.
 *
 * <p>The sole minter of message refs, which makes it the authority that
 * {@link io.jclaw.contracts.loop.LoopExit} validation consults. A driver claiming completion with
 * a ref that does not resolve here does not complete the run.
 *
 * <p>Drafts are written like any other line but filtered from {@link #history}: the transcript is
 * append-only, so a superseded draft is shadowed rather than deleted. That keeps the file honest
 * about what happened while keeping the conversation clean for the model and the reader.
 */
public final class JsonlThreadService implements ThreadService {

    private static final Logger log = LoggerFactory.getLogger(JsonlThreadService.class);

    private final RowStore file;
    private final Clock clock;

    public JsonlThreadService(RowStore file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ThreadId ensureThread(ThreadId thread) {
        return Objects.requireNonNull(thread, "thread");
    }

    @Override
    public AcceptedMessageRef acceptInbound(ThreadId thread, ChatMessage message) {
        MessageId id = append(thread, message, false);
        return AcceptedMessageRef.of(id);
    }

    @Override
    public LoopMessageRef appendAssistant(ThreadId thread, ChatMessage message, boolean draft) {
        MessageId id = append(thread, message, draft);
        return LoopMessageRef.of(id);
    }

    @Override
    public Optional<ThreadMessage> resolve(LoopMessageRef ref) {
        Objects.requireNonNull(ref, "ref");
        return findById(ref.value());
    }

    @Override
    public Optional<ThreadMessage> resolveAccepted(AcceptedMessageRef ref) {
        Objects.requireNonNull(ref, "ref");
        return findById(ref.value());
    }

    @Override
    public List<ThreadMessage> history(ThreadId thread, int limit) {
        Objects.requireNonNull(thread, "thread");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        List<ThreadMessage> all = readAll().stream()
                .filter(message -> message.thread().equals(thread))
                .filter(ThreadMessage::isFinal)
                .toList();

        // Keep the most recent when the thread is longer than the limit; the model needs the end
        // of the conversation, not the beginning.
        int from = Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    @Override
    public List<ThreadId> listThreads(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        Map<ThreadId, Instant> lastSeen = new LinkedHashMap<>();
        for (ThreadMessage message : readAll()) {
            lastSeen.merge(message.thread(), message.createdAt(),
                    (existing, candidate) -> candidate.isAfter(existing) ? candidate : existing);
        }
        return lastSeen.entrySet().stream()
                .sorted(Map.Entry.<ThreadId, Instant>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();
    }

    /** Distinct thread ids, preserving first-seen order. Used by the CLI's thread listing. */
    public List<ThreadId> allThreads() {
        return List.copyOf(new LinkedHashSet<>(readAll().stream().map(ThreadMessage::thread).toList()));
    }

    private MessageId append(ThreadId thread, ChatMessage message, boolean draft) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(message, "message");

        MessageId id = MessageId.fresh();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id.value());
        record.put("thread", thread.value());
        record.put("createdAt", clock.instant().toString());
        record.put("draft", draft);
        record.put("message", MessageCodec.encode(message));
        file.append(record);
        log.trace("thread {}: {} message {} appended ({} chars, draft={})",
                thread.value(), message.role(), id.value(),
                message.displayText().length(), draft);
        return id;
    }

    private Optional<ThreadMessage> findById(String id) {
        return readAll().stream()
                .filter(message -> message.id().value().equals(id))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private List<ThreadMessage> readAll() {
        List<ThreadMessage> messages = new ArrayList<>();
        for (Map<String, Object> record : file.readAll()) {
            try {
                messages.add(new ThreadMessage(
                        new MessageId(String.valueOf(record.get("id"))),
                        new ThreadId(String.valueOf(record.get("thread"))),
                        MessageCodec.decode((Map<String, Object>) record.get("message")),
                        Instant.parse(String.valueOf(record.get("createdAt"))),
                        record.get("draft") instanceof Boolean flag && flag));
            } catch (RuntimeException e) {
                // A damaged line loses one message, not the conversation.
            }
        }
        messages.sort(Comparator.comparing(ThreadMessage::createdAt));
        return messages;
    }
}
