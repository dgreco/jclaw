// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.scheduling;

import io.jclaw.contracts.turn.RunStore.RunRecord;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunSchedulingTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final RunScheduling.Caps TWO = new RunScheduling.Caps(2);

    private static RunRecord queued(String id, String thread, int secondsAfterT0) {
        return new RunRecord(
                new TurnRunId(id), TurnScope.local("proj", new ThreadId(thread)), TurnStatus.QUEUED,
                "m", "p", T0.plusSeconds(secondsAfterT0), Optional.empty(), Optional.empty());
    }

    private static RunRecord withTenant(RunRecord record, String tenant) {
        return new RunRecord(record.run(),
                new TurnScope(tenant, "default", record.scope().project(), record.scope().thread()),
                record.status(), record.model(), record.systemPrompt(), record.submittedAt(),
                record.finishedAt(), record.lease());
    }

    private static List<String> ids(List<RunRecord> records) {
        return records.stream().map(record -> record.run().value()).toList();
    }

    @Test
    @DisplayName("oldest submissions start first, up to the free slots")
    void oldestFirstWithinSlots() {
        List<RunRecord> queued = List.of(
                queued("c", "t3", 30), queued("a", "t1", 10), queued("b", "t2", 20));

        assertEquals(List.of("a", "b"), ids(RunScheduling.select(queued, Set.of(), 0, TWO)));
        assertEquals(List.of("a"), ids(RunScheduling.select(queued, Set.of(), 1, TWO)));
        assertTrue(RunScheduling.select(queued, Set.of(), 2, TWO).isEmpty());
    }

    @Test
    @DisplayName("a thread with a run in flight gets nothing more this pass")
    void skipsBusyThreads() {
        List<RunRecord> queued = List.of(queued("a", "busy", 10), queued("b", "free", 20));
        Set<String> busy = Set.of(TurnScope.local("proj", new ThreadId("busy")).lockKey());

        assertEquals(List.of("b"), ids(RunScheduling.select(queued, busy, 1, TWO)));
    }

    @Test
    @DisplayName("two queued runs on one thread are never started together")
    void onePerThreadPerPass() {
        List<RunRecord> queued = List.of(
                queued("a1", "t", 10), queued("a2", "t", 20), queued("b", "u", 30));

        assertEquals(List.of("a1", "b"), ids(RunScheduling.select(queued, Set.of(), 0, TWO)),
                "the second run on thread t waits for the first; the slot goes to another thread");
    }

    @Test
    @DisplayName("a per-tenant cap keeps one busy tenant from taking every slot")
    void perTenantCap() {
        List<RunRecord> queued = List.of(
                withTenant(queued("a1", "alice:t1", 10), "alice"),
                withTenant(queued("a2", "alice:t2", 20), "alice"),
                withTenant(queued("b1", "bob:t1", 30), "bob"));
        RunScheduling.Caps caps = new RunScheduling.Caps(3, 1);

        assertEquals(List.of("a1", "b1"), ids(RunScheduling.select(queued, Set.of(), 0, java.util.Map.of(), caps)),
                "alice gets one slot, bob the next; alice's second waits");
        assertEquals(List.of("b1"), ids(RunScheduling.select(
                queued, Set.of(), 1, java.util.Map.of("alice", 1), caps)),
                "an in-flight alice run already fills her share");
    }

    @Test
    @DisplayName("caps must be positive")
    void capsValidation() {
        assertThrows(IllegalArgumentException.class, () -> new RunScheduling.Caps(0));
        assertThrows(IllegalArgumentException.class,
                () -> RunScheduling.select(List.of(), Set.of(), -1, TWO));
    }
}
