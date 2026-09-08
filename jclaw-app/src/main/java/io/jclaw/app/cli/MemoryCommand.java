package io.jclaw.app.cli;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.memory.MemoryRecord;
import io.jclaw.contracts.memory.MemoryStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.domain.retrieval.MemoryRanking;
import io.jclaw.tools.MemoryTools;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Inspects and edits the agent's memory.
 *
 * <p>Every subcommand resolves its scope through {@link JclawRuntime#scopeFor}, so the CLI reads
 * and writes exactly the memories the agent does. Constructing a scope independently here is how
 * a listing quietly diverges from what the model actually sees.
 */
@Component
@Command(
        name = "memory",
        description = "Inspect and edit remembered facts.",
        mixinStandardHelpOptions = true,
        subcommands = {
                MemoryCommand.Write.class,
                MemoryCommand.Search.class,
                MemoryCommand.ListAll.class,
                MemoryCommand.Forget.class
        })
public class MemoryCommand implements Runnable {

    /** Memories are project-scoped, so any thread resolves the same set. */
    static final ThreadId SCOPE_THREAD = new ThreadId("memory");

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    private static void print(List<MemoryRecord> records) {
        if (records.isEmpty()) {
            System.out.println("(no memories)");
            return;
        }
        for (MemoryRecord record : records) {
            System.out.printf("%s  %s%n", record.id().value(), record.createdAt());
            System.out.println("    " + record.preview(200)
                    + (record.tags().isEmpty() ? "" : "  [" + String.join(", ", record.tags()) + "]"));
        }
        System.out.println();
        System.out.println(records.size() + " shown.");
    }

    @Component
    @Command(name = "write", description = "Remember a fact.", mixinStandardHelpOptions = true)
    public static class Write implements Callable<Integer> {

        private final MemoryStore store;
        private final JclawRuntime runtime;

        @Parameters(arity = "1..*", description = "The fact to remember.")
        private String[] text;

        @Option(names = "--tags", description = "Comma-separated labels.")
        private String tags = "";

        public Write(MemoryStore store, JclawRuntime runtime) {
            this.store = store;
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            TurnScope scope = runtime.scopeFor(SCOPE_THREAD);
            MemoryRecord.MemoryId id =
                    store.write(scope, String.join(" ", text), MemoryTools.parseTags(tags));
            System.out.println("Remembered " + id.value());
            return 0;
        }
    }

    @Component
    @Command(name = "search", description = "Search memories (lexical + recency, RRF-fused).",
            mixinStandardHelpOptions = true)
    public static class Search implements Callable<Integer> {

        private final MemoryStore store;
        private final JclawRuntime runtime;
        private final Clock clock;

        @Parameters(arity = "1..*", description = "Query terms.")
        private String[] query;

        @Option(names = {"-n", "--limit"}, description = "Maximum results. Default 5.")
        private int limit = 5;

        public Search(MemoryStore store, JclawRuntime runtime, Clock clock) {
            this.store = store;
            this.runtime = runtime;
            this.clock = clock;
        }

        @Override
        public Integer call() {
            TurnScope scope = runtime.scopeFor(SCOPE_THREAD);
            print(MemoryRanking.rank(
                    store.all(scope), String.join(" ", query), clock.instant(), limit));
            return 0;
        }
    }

    @Component
    @Command(name = "list", description = "List memories, newest first.",
            mixinStandardHelpOptions = true)
    public static class ListAll implements Callable<Integer> {

        private final MemoryStore store;
        private final JclawRuntime runtime;

        @Option(names = {"-n", "--limit"}, description = "Maximum results. Default 20.")
        private int limit = 20;

        public ListAll(MemoryStore store, JclawRuntime runtime) {
            this.store = store;
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            List<MemoryRecord> all = store.all(runtime.scopeFor(SCOPE_THREAD));
            print(all.stream().limit(Math.max(0, limit)).toList());
            return 0;
        }
    }

    @Component
    @Command(name = "forget", description = "Delete a memory by id.",
            mixinStandardHelpOptions = true)
    public static class Forget implements Callable<Integer> {

        private final MemoryStore store;

        @Parameters(index = "0", description = "Memory id, as shown by 'jclaw memory list'.")
        private String id;

        public Forget(MemoryStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            boolean removed = store.delete(new MemoryRecord.MemoryId(id));
            if (!removed) {
                System.err.println("jclaw: no such memory: " + id);
                return 1;
            }
            System.out.println("Forgot " + id);
            return 0;
        }
    }
}
