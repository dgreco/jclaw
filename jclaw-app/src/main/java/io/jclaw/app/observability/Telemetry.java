package io.jclaw.app.observability;

import io.jclaw.contracts.event.JclawEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide metrics, fed by the event log and rendered in the Prometheus exposition format.
 *
 * <p>Every number here is derived from an event the audit log already holds, so the metrics
 * cannot disagree with the log and cannot carry anything the log does not: names, enums, and
 * counts, never a prompt or a path. Counters and timers live in memory for the life of the
 * process, which is what a scraper expects; history is the log's job.
 *
 * <p>Hand-rolled rather than a metrics library because the surface is small (counters and
 * timers with a handful of label sets) and a dependency would bring reflection the native image
 * would have to be told about.
 */
public final class Telemetry {

    private static final class Timer {
        final LongAdder count = new LongAdder();
        final LongAdder sumMillis = new LongAdder();
        volatile long maxMillis;

        void record(long millis) {
            count.increment();
            sumMillis.add(millis);
            if (millis > maxMillis) {
                maxMillis = millis;
            }
        }
    }

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    /** Increments {@code name} with the given label pairs. */
    public void count(String name, String... labels) {
        counters.computeIfAbsent(series(name, labels), ignored -> new LongAdder()).increment();
    }

    /** Adds {@code amount} to {@code name} with the given label pairs. */
    public void add(String name, long amount, String... labels) {
        counters.computeIfAbsent(series(name, labels), ignored -> new LongAdder()).add(amount);
    }

    /** Records one duration against {@code name} with the given label pairs. */
    public void time(String name, long millis, String... labels) {
        timers.computeIfAbsent(series(name, labels), ignored -> new Timer()).record(Math.max(0, millis));
    }

    /** Updates the metrics for one audit event. */
    public void record(JclawEvent event) {
        Objects.requireNonNull(event, "event");
        switch (event) {
            case JclawEvent.TurnSubmitted e -> count("jclaw_runs_submitted_total", "tenant", e.scope().tenant());
            case JclawEvent.RunClaimed ignored -> count("jclaw_runs_claimed_total");
            case JclawEvent.ModelCalled e -> {
                count("jclaw_model_calls_total", "provider", e.providerId(), "model", e.modelId());
                add("jclaw_model_tokens_total", e.usage().inputTokens(), "kind", "input");
                add("jclaw_model_tokens_total", e.usage().outputTokens(), "kind", "output");
                add("jclaw_model_tokens_total", e.usage().cacheReadTokens(), "kind", "cache_read");
                time("jclaw_model_latency_millis", e.latencyMillis(), "provider", e.providerId());
            }
            case JclawEvent.ModelFailed e -> count("jclaw_model_failures_total", "provider", e.providerId(),
                    "category", e.category());
            case JclawEvent.CapabilityInvoked e -> {
                count("jclaw_capability_calls_total", "capability", e.capability().value(), "outcome", e.outcome());
                time("jclaw_capability_latency_millis", e.latencyMillis(), "capability", e.capability().value());
            }
            case JclawEvent.InjectionDetected e -> count("jclaw_injections_total", "severity", e.severity(),
                    "action", e.action());
            case JclawEvent.SecretInjected e -> count("jclaw_secrets_injected_total", "secret", e.secret());
            case JclawEvent.HookFired e -> count("jclaw_hooks_fired_total", "hook", e.hook(), "stage", e.stage(),
                    "action", e.action());
            case JclawEvent.GateRaised e -> count("jclaw_gates_raised_total", "kind", e.gate().name());
            case JclawEvent.GateResolved e -> count("jclaw_gates_resolved_total", "approved", String.valueOf(e.approved()));
            case JclawEvent.CheckpointWritten e -> count("jclaw_checkpoints_total", "kind", e.kind().name());
            case JclawEvent.RunFinished e -> {
                count("jclaw_runs_finished_total", "status", e.status().name());
                e.failure().ifPresent(f -> count("jclaw_run_failures_total", "kind", f.category()));
                add("jclaw_run_iterations_total", e.iterations());
            }
        }
    }

    /** The metrics in Prometheus text format (version 0.0.4). */
    public String prometheus() {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(counters).forEach((series, value) ->
                out.append(series).append(' ').append(value.sum()).append('\n'));
        new TreeMap<>(timers).forEach((series, timer) -> {
            int brace = series.indexOf('{');
            String name = brace < 0 ? series : series.substring(0, brace);
            String labels = brace < 0 ? "" : series.substring(brace);
            out.append(name).append("_count").append(labels).append(' ').append(timer.count.sum()).append('\n');
            out.append(name).append("_sum").append(labels).append(' ').append(timer.sumMillis.sum()).append('\n');
            out.append(name).append("_max").append(labels).append(' ').append(timer.maxMillis).append('\n');
        });
        return out.toString();
    }

    private static String series(String name, String... labels) {
        if (labels.length % 2 != 0) {
            throw new IllegalArgumentException("labels come in pairs");
        }
        if (labels.length == 0) {
            return name;
        }
        StringBuilder out = new StringBuilder(name).append('{');
        for (int i = 0; i < labels.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append(labels[i]).append("=\"")
                    .append(labels[i + 1].toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return out.append('}').toString();
    }
}
