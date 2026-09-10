// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.projection;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.CheckpointKind;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.model.ModelExchange.Usage;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunProjectionTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final TurnRunId RUN = new TurnRunId("run_a");
    private static final TurnRunId OTHER = new TurnRunId("run_b");
    private static final TurnScope SCOPE = TurnScope.local("proj", new ThreadId("t"));

    @Test
    @DisplayName("a full lifecycle folds into status, counts, and usage")
    void fullLifecycle() {
        List<JclawEvent> events = List.of(
                new JclawEvent.TurnSubmitted(T0, RUN, SCOPE),
                new JclawEvent.RunClaimed(T0.plusSeconds(1), RUN, "w1", T0.plusSeconds(120)),
                new JclawEvent.CheckpointWritten(T0.plusSeconds(1), RUN, CheckpointKind.BEFORE_MODEL, 0),
                new JclawEvent.ModelCalled(T0.plusSeconds(2), RUN, "mock", "m", Usage.of(10, 5), 30),
                new JclawEvent.CapabilityInvoked(T0.plusSeconds(3), RUN, CapabilityId.builtin("read_file"),
                        EffectClass.READ_LOCAL, "fp", "ok", 4),
                new JclawEvent.InjectionDetected(T0.plusSeconds(3), RUN, CapabilityId.builtin("read_file"),
                        "HIGH", 2, "sanitized"),
                new JclawEvent.ModelCalled(T0.plusSeconds(4), OTHER, "mock", "m", Usage.of(99, 99), 1),
                new JclawEvent.ModelCalled(T0.plusSeconds(5), RUN, "mock", "m", Usage.of(20, 7), 40),
                new JclawEvent.RunFinished(T0.plusSeconds(6), RUN, TurnStatus.COMPLETED, Optional.empty(),
                        Usage.of(30, 12), 1));

        RunProjection.RunView view = RunProjection.fold(RUN, events);

        assertEquals(Optional.of(TurnStatus.COMPLETED), view.status());
        assertEquals(Optional.of(SCOPE), view.scope());
        assertEquals(2, view.modelCalls(), "another run's events are ignored");
        assertEquals(42, view.usage().total());
        assertEquals(1, view.capabilities().size());
        assertEquals("ok", view.capabilities().get(0).outcome());
        assertEquals(2, view.injectionFindings());
        assertEquals(1, view.checkpoints());
        assertEquals(1, view.iterations());
        assertEquals(Optional.of(T0.plusSeconds(6)), view.finishedAt());
        assertTrue(view.openGate().isEmpty());
    }

    @Test
    @DisplayName("a raised gate parks the view and shows as the open gate until resolved")
    void gates() {
        List<JclawEvent> parked = List.of(
                new JclawEvent.TurnSubmitted(T0, RUN, SCOPE),
                new JclawEvent.RunClaimed(T0, RUN, "w1", T0.plusSeconds(120)),
                new JclawEvent.GateRaised(T0.plusSeconds(1), RUN, GateKind.APPROVAL, "gate_1"));

        RunProjection.RunView view = RunProjection.fold(RUN, parked);
        assertEquals(Optional.of(TurnStatus.BLOCKED_APPROVAL), view.status());
        assertEquals("gate_1", view.openGate().orElseThrow().id());

        RunProjection.RunView resolved = RunProjection.fold(RUN, List.of(
                parked.get(0), parked.get(1), parked.get(2),
                new JclawEvent.GateResolved(T0.plusSeconds(2), RUN, "gate_1", true),
                new JclawEvent.RunClaimed(T0.plusSeconds(3), RUN, "w2", T0.plusSeconds(200))));
        assertEquals(Optional.of(TurnStatus.RUNNING), resolved.status());
        assertEquals(Optional.of(true), resolved.gates().get(0).approved());
        assertTrue(resolved.openGate().isEmpty());
    }

    @Test
    @DisplayName("no events is an empty view, not an error")
    void empty() {
        RunProjection.RunView view = RunProjection.fold(RUN, List.of());
        assertTrue(view.status().isEmpty());
        assertEquals(0, view.modelCalls());
    }
}
