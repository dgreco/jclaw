package io.jclaw.tools;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.HandlerError;
import io.jclaw.contracts.memory.MemoryRecord;
import io.jclaw.contracts.memory.MemoryStore;
import io.jclaw.domain.retrieval.MemoryRanking;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Memory capabilities: let the agent remember and recall across turns.
 *
 * <p>The store arrives by constructor rather than through
 * {@link CapabilityHandler.HandlerContext}. That surface is deliberately narrow — a path resolver,
 * an egress check, an output budget — and widening it for every new dependency would erode the one
 * property that makes it easy to reason about what a tool can reach.
 *
 * <p>{@code memory_write} is {@link EffectClass#WRITE_LOCAL}: it persists, so under the default
 * policy it asks first. {@code memory_search} is {@link EffectClass#READ_LOCAL} and runs freely.
 *
 * <p>Retrieved memories are model-visible content and therefore a prompt-injection surface: text
 * written during one turn is read back into a later prompt. The store enforces project scoping so
 * that surface cannot cross projects, and results stay bounded so a single large memory cannot
 * crowd out the conversation.
 */
public final class MemoryTools {

    /** Maximum memories returned by one search. */
    private static final int MAX_RESULTS = 10;

    /** Maximum characters of each memory included in a search result. */
    private static final int PREVIEW_CHARS = 500;

    private MemoryTools() {
    }

    public static List<CapabilityHandler> all(MemoryStore store, Clock clock) {
        return List.of(new Write(store), new Search(store, clock));
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

        public Write(MemoryStore store) {
            this.store = Objects.requireNonNull(store, "store");
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
            MemoryRecord.MemoryId id = store.write(invocation.scope(), text, tags);
            return Result.ok("Remembered as " + id.value()
                    + (tags.isEmpty() ? "" : " [" + String.join(", ", tags) + "]"));
        }
    }

    /** Hybrid lexical + recency search over the project's memories. */
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

        public Search(MemoryStore store, Clock clock) {
            this.store = Objects.requireNonNull(store, "store");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            int limit = Math.clamp(invocation.intArg("limit", 5), 1, MAX_RESULTS);
            List<MemoryRecord> ranked = MemoryRanking.rank(
                    store.all(invocation.scope()),
                    invocation.stringArg("query", ""),
                    clock.instant(),
                    limit);

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
     * Splits a comma-separated tag list. Blank entries are dropped by {@link MemoryRecord}.
     *
     * <p>Public so the CLI parses tags exactly as the tool does — two parsers would eventually
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
