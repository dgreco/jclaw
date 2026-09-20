// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.bootstrap.observability.ObservedEventLog;
import io.jclaw.ports.event.EventLog;
import io.jclaw.ports.event.JclawEvent;
import io.jclaw.ports.turn.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * How many tokens each tenant has spent, and whether they may spend more.
 *
 * <p>Counted from finished runs in the audit log, which is the only record that cannot disagree
 * with what actually happened. A run's usage is on its {@code run.finished} event and its tenant
 * is on its run record, so the two are joined here rather than duplicated into a third place.
 *
 * <p>The budget is a floor under the operator's bill, not a scheduler input. It is checked at
 * admission, so a tenant over budget cannot start a turn, and never mid-run, because stopping a
 * turn halfway spends the tokens and produces nothing.
 *
 * <p>Kept in memory and seeded once from the log. That means a restart re-reads the log rather
 * than trusting a running total nobody can audit, and it means the count is only as long as
 * retention keeps events, which is the honest limit of counting this way.
 */
public final class TenantLedger {

    private static final Logger log = LoggerFactory.getLogger(TenantLedger.class);

    private final Map<String, LongAdder> spent = new ConcurrentHashMap<>();
    private final RunStore runs;
    private final long budget;

    /** @param budget tokens a tenant may spend, or zero for no limit */
    public TenantLedger(EventLog events, RunStore runs, long budget) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.budget = Math.max(0, budget);
        Objects.requireNonNull(events, "events");
        if (this.budget == 0) {
            return; // nothing to count for, so nothing is counted
        }
        seed(events);
        if (events instanceof ObservedEventLog observed) {
            observed.addListener(this::record);
        } else {
            log.debug("ledger: event log is not observable; the budget will not see new runs");
        }
    }

    private void seed(EventLog events) {
        int counted = 0;
        for (EventLog.Entry entry : events.readFrom(EventLog.EventCursor.START, Integer.MAX_VALUE)) {
            if (entry.event() instanceof JclawEvent.RunFinished) {
                record(entry.event());
                counted++;
            }
        }
        log.debug("ledger: seeded from {} finished run(s)", counted);
    }

    void record(JclawEvent event) {
        if (!(event instanceof JclawEvent.RunFinished finished)) {
            return;
        }
        runs.find(finished.run()).ifPresent(record -> spent
                .computeIfAbsent(record.scope().tenant(), ignored -> new LongAdder())
                .add(finished.totalUsage().total()));
    }

    /** Tokens this tenant has spent on finished runs. */
    public long spentBy(String tenant) {
        LongAdder adder = spent.get(Objects.requireNonNull(tenant, "tenant"));
        return adder == null ? 0 : adder.sum();
    }

    /** The configured budget, or zero when there is none. */
    public long budget() {
        return budget;
    }

    /** Whether this tenant may start another turn. Always true when no budget is set. */
    public boolean permits(String tenant) {
        return budget == 0 || spentBy(tenant) < budget;
    }
}
