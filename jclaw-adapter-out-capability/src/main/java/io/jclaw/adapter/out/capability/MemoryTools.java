// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.capability;

import io.jclaw.ports.Result;
import io.jclaw.ports.capability.CapabilityDescriptor;
import io.jclaw.ports.capability.CapabilityHandler;
import io.jclaw.ports.capability.CapabilityInvocation;
import io.jclaw.ports.capability.EffectClass;
import io.jclaw.ports.capability.HandlerError;
import io.jclaw.ports.memory.Embedding;
import io.jclaw.ports.memory.EmbeddingProvider;
import io.jclaw.ports.memory.MemoryRecord;
import io.jclaw.ports.memory.MemoryStore;
import io.jclaw.ports.model.ModelProvider.ProviderFailure;
import io.jclaw.ports.turn.TurnScope;
import io.jclaw.domain.retrieval.MemoryRanking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Memory capabilities: let the agent remember and recall across turns.
 *
 * <p>The store and the embedding provider arrive by constructor rather than through
 * {@link CapabilityHandler.HandlerContext}. That surface is deliberately narrow, a path resolver,
 * an egress check, an output budget, and widening it for every new dependency would erode the one
 * property that makes it easy to reason about what a tool can reach.
 *
 * <p>{@code memory_write} is {@link EffectClass#WRITE_LOCAL}: it persists, so under the default
 * policy it asks first. {@code memory_search} is {@link EffectClass#READ_LOCAL} and runs freely.
 *
 * <p>Embeddings are best effort on both paths. A write whose embedding request fails is still
 * written, without a vector; a search whose query cannot be embedded still ranks lexically and by
 * recency. Vector similarity is an extra signal into the fusion, never a precondition, so a
 * misconfigured or offline embedding server degrades retrieval quality rather than breaking memory.
 *
 * <p>Retrieved memories are model-visible content and therefore a prompt-injection surface: text
 * written during one turn is read back into a later prompt. The store enforces project scoping so
 * that surface cannot cross projects, and results stay bounded so a single large memory cannot
 * crowd out the conversation.
 */
public final class MemoryTools {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);

    /** Maximum memories returned by one search. */
    private static final int MAX_RESULTS = 10;

    /** Maximum characters of each memory included in a search result. */
    private static final int PREVIEW_CHARS = 500;

    private MemoryTools() {
    }

    public static List<CapabilityHandler> all(MemoryStore store, Clock clock, EmbeddingProvider embeddings) {
        return List.of(new Write(store, embeddings), new Search(store, clock, embeddings));
    }

    /** Persists a memory in the current project scope. */
    public static final class Write implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "memory_write",
                "Remember a fact for later turns. Use for durable project knowledge, "
                        + "not for transient details already in this conversation.",
                EffectClass.WRITE_LOCAL,
                Schemas.object(
                        Schemas.properties(
                                "text", Schemas.string("The fact to remember."),
                                "tags", Schemas.string("Optional comma-separated labels.")),
                        List.of("text")));

        private final MemoryStore store;
        private final EmbeddingProvider embeddings;

        public Write(MemoryStore store, EmbeddingProvider embeddings) {
            this.store = Objects.requireNonNull(store, "store");
            this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            String text = invocation.stringArg("text", "");
            if (text.isBlank()) {
                return Result.err(HandlerError.failed("text_required"));
            }
            List<String> tags = parseTags(invocation.stringArg("tags", ""));
            MemoryRecord.MemoryId id = write(store, embeddings, invocation.scope(), text, tags);
            return Result.ok("Remembered as " + id.value()
                    + (tags.isEmpty() ? "" : " [" + String.join(", ", tags) + "]"));
        }
    }

    /** Hybrid lexical + recency + vector search over the project's memories. */
    public static final class Search implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "memory_search",
                "Search remembered facts. Returns the most relevant and recent matches.",
                EffectClass.READ_LOCAL,
                Schemas.object(
                        Schemas.properties(
                                "query", Schemas.string("What to look for. Omit to list recent memories."),
                                "limit", Schemas.integer("Maximum results.", 1, MAX_RESULTS)),
                        List.of()));

        private final MemoryStore store;
        private final Clock clock;
        private final EmbeddingProvider embeddings;

        public Search(MemoryStore store, Clock clock, EmbeddingProvider embeddings) {
            this.store = Objects.requireNonNull(store, "store");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            int limit = Math.clamp(invocation.intArg("limit", 5), 1, MAX_RESULTS);
            List<MemoryRecord> ranked = search(
                    store, embeddings, invocation.scope(), invocation.stringArg("query", ""),
                    clock.instant(), limit);

            if (ranked.isEmpty()) {
                return Result.ok("(no matching memories)");
            }
            return Result.ok(ranked.stream()
                    .map(record -> "- " + record.preview(PREVIEW_CHARS)
                            + (record.tags().isEmpty() ? "" : " [" + String.join(", ", record.tags()) + "]"))
                    .collect(Collectors.joining("\n")));
        }
    }

    /**
     * Writes a memory, embedding it when a provider is configured.
     *
     * <p>Public so the CLI writes exactly as the tool does. Two write paths would eventually
     * disagree about whether a memory gets a vector.
     */
    public static MemoryRecord.MemoryId write(
            MemoryStore store, EmbeddingProvider embeddings, TurnScope scope, String text, List<String> tags) {
        return store.write(scope, text, tags, embedQuietly(embeddings, text));
    }

    /**
     * Ranks a project's memories for a query: lexical, recency, and, when the query can be
     * embedded, vector similarity, fused by RRF. Shared by the tool and the CLI.
     */
    public static List<MemoryRecord> search(
            MemoryStore store, EmbeddingProvider embeddings, TurnScope scope, String query,
            Instant now, int limit) {
        Optional<Embedding> queryEmbedding = query.isBlank()
                ? Optional.empty()
                : embedQuietly(embeddings, query);
        return MemoryRanking.rank(store.all(scope), query, now, limit, queryEmbedding);
    }

    /**
     * Embeds when possible, empty otherwise.
     *
     * <p>The failure is logged, not surfaced: an embedding is an enhancement to a memory, and the
     * memory must not be lost because a vector could not be attached to it.
     */
    public static Optional<Embedding> embedQuietly(EmbeddingProvider embeddings, String text) {
        if (!embeddings.available()) {
            return Optional.empty();
        }
        Result<Embedding, ProviderFailure> result = embeddings.embed(text);
        return switch (result) {
            case Result.Ok<Embedding, ProviderFailure> ok -> Optional.of(ok.value());
            case Result.Err<Embedding, ProviderFailure> err -> {
                log.debug("embedding via {} failed: {} {}", embeddings.id(), err.error().kind(),
                        err.error().detail().orElse(""));
                yield Optional.empty();
            }
        };
    }

    /**
     * Splits a comma-separated tag list. Blank entries are dropped by {@link MemoryRecord}.
     *
     * <p>Public so the CLI parses tags exactly as the tool does. Two parsers would eventually
     * disagree, and a tag that means one thing to the agent and another to the operator is worse
     * than no tag.
     */
    public static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(tag -> !tag.isEmpty()).toList();
    }
}
