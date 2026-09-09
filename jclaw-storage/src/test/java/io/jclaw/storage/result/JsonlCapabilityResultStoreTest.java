package io.jclaw.storage.result;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.CapabilityResultStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.storage.jsonl.JsonlFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlCapabilityResultStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final TurnRunId RUN = new TurnRunId("run_test");

    @TempDir
    Path dir;

    private JsonlCapabilityResultStore store() {
        return new JsonlCapabilityResultStore(
                new JsonlFile(dir.resolve("results.jsonl")), Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static CapabilityInvocation invocation() {
        return new CapabilityInvocation(
                CapabilityId.builtin("read_file"), "call-1", Map.of("path", "README.md"),
                TurnScope.local("proj", new ThreadId("t")), RUN);
    }

    @Test
    @DisplayName("a result minted by one process resolves from another")
    void survivesTheProcess() {
        LoopResultRef ref = store().store(RUN, invocation(), "line one\nline two", true);

        // A fresh instance over the same file stands in for a second process.
        CapabilityResultStore.StoredResult found = store().resolve(ref).orElseThrow();
        assertEquals(RUN, found.run());
        assertEquals(CapabilityId.builtin("read_file"), found.capability());
        assertEquals(invocation().fingerprint(), found.fingerprint());
        assertEquals("line one\nline two", found.payload(), "newlines survive the JSONL encoding");
        assertTrue(found.truncated());
        assertEquals(T0, found.storedAt());
    }

    @Test
    @DisplayName("a ref nobody minted resolves to nothing")
    void fabricatedRefIsNotEvidence() {
        JsonlCapabilityResultStore store = store();
        store.store(RUN, invocation(), "real", false);

        assertTrue(store.resolve(new LoopResultRef("res_fabricated")).isEmpty());
        assertEquals(1, store.size());
    }
}
