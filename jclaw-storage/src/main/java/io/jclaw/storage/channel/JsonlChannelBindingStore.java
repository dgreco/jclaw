// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.channel;

import io.jclaw.contracts.channel.ChannelBindingStore;
import io.jclaw.contracts.channel.ReplyTarget;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.storage.rows.RowStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Channel bindings as rows, last write per thread wins. */
public final class JsonlChannelBindingStore implements ChannelBindingStore {

    private static final String KIND_BOUND = "bound";
    private static final String KIND_UNBOUND = "unbound";

    private final RowStore rows;
    private final Clock clock;

    public JsonlChannelBindingStore(RowStore rows, Clock clock) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void bind(ThreadId thread, ReplyTarget target) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(target, "target");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_BOUND);
        row.put("thread", thread.value());
        row.put("adapter", target.adapter());
        row.put("conversation", target.conversation());
        target.thread().ifPresent(t -> row.put("channelThread", t));
        row.put("at", clock.instant().toString());
        rows.append(row);
    }

    @Override
    public synchronized Optional<ReplyTarget> find(ThreadId thread) {
        Objects.requireNonNull(thread, "thread");
        return Optional.ofNullable(replay().get(thread.value()));
    }

    @Override
    public synchronized List<ThreadId> boundThreads() {
        List<ThreadId> threads = new ArrayList<>();
        replay().keySet().forEach(name -> threads.add(new ThreadId(name)));
        return List.copyOf(threads);
    }

    @Override
    public synchronized boolean unbind(ThreadId thread) {
        Objects.requireNonNull(thread, "thread");
        if (!replay().containsKey(thread.value())) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_UNBOUND);
        row.put("thread", thread.value());
        row.put("at", clock.instant().toString());
        rows.append(row);
        return true;
    }

    private Map<String, ReplyTarget> replay() {
        Map<String, ReplyTarget> live = new LinkedHashMap<>();
        for (Map<String, Object> row : rows.readAll()) {
            String thread = String.valueOf(row.get("thread"));
            switch (String.valueOf(row.get("kind"))) {
                case KIND_BOUND -> {
                    try {
                        live.put(thread, new ReplyTarget(
                                String.valueOf(row.get("adapter")),
                                String.valueOf(row.get("conversation")),
                                Optional.ofNullable(row.get("channelThread")).map(Object::toString)));
                    } catch (RuntimeException e) {
                        // A damaged row loses one binding, not the channel.
                    }
                }
                case KIND_UNBOUND -> live.remove(thread);
                default -> { }
            }
        }
        return live;
    }
}
