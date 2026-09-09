package io.jclaw.app.runtime;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.domain.retention.Retention;
import io.jclaw.storage.jsonl.JsonlFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies {@link Retention} to the stores that grow with every run.
 *
 * <p>Three stores are swept: capability results, the event log, and checkpoints. Each is rewritten
 * in place with only the rows the rule keeps, atomically, so a crash mid-sweep leaves either the
 * old file or the new one. The transcript is never swept: it is the conversation, and dropping
 * part of it silently would change what a thread means.
 *
 * <p>The rule is decided per row by the pure function; this class only supplies the facts. A
 * row's run is looked up once per sweep, and a run the store cannot find is treated as
 * unfinished, which keeps the row.
 *
 * <p>Rewriting is the one departure from append-only in the whole storage layer, and it has a
 * known window: an append by another process between the read and the rename lands in the file
 * that is about to be replaced. The window is milliseconds and the loss is one audit row, never
 * run state (the run store is not swept), but a sweep is still best run from the worker or an
 * idle shell rather than under load.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** What a sweep did to one store. */
    public record Swept(String store, int kept, int dropped, boolean applied) {
    }

    private final JclawProperties properties;
    private final RunStore runs;
    private final Clock clock;

    public RetentionService(JclawProperties properties, RunStore runs, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Sweeps every retained store. With {@code dryRun}, counts without rewriting. */
    public List<Swept> sweep(boolean dryRun) {
        Instant now = clock.instant();
        Map<String, Boolean> finished = new HashMap<>();
        List<Swept> report = new ArrayList<>();
        report.add(sweep("results", new JsonlFile(properties.resultsPath()), "storedAt",
                properties.retentionResults(), now, finished, dryRun));
        report.add(sweep("events", new JsonlFile(properties.eventLogPath()), "at",
                properties.retentionEvents(), now, finished, dryRun));
        report.add(sweep("checkpoints", new JsonlFile(properties.checkpointsPath()), "writtenAt",
                properties.retentionCheckpoints(), now, finished, dryRun));
        return List.copyOf(report);
    }

    private Swept sweep(
            String name, JsonlFile file, String timestampField, Duration maxAge,
            Instant now, Map<String, Boolean> finished, boolean dryRun) {

        List<Map<String, Object>> rows = file.readAll();
        if (Retention.keepsForever(maxAge)) {
            return new Swept(name, rows.size(), 0, false);
        }
        List<Map<String, Object>> kept = new ArrayList<>(rows.size());
        int dropped = 0;
        for (Map<String, Object> row : rows) {
            if (expendable(row, timestampField, maxAge, now, finished)) {
                dropped++;
            } else {
                kept.add(row);
            }
        }
        boolean applied = dropped > 0 && !dryRun;
        if (applied) {
            file.rewrite(kept);
            log.debug("retention: {} rewritten, kept {} dropped {}", name, kept.size(), dropped);
        }
        return new Swept(name, kept.size(), dropped, applied);
    }

    private boolean expendable(
            Map<String, Object> row, String timestampField, Duration maxAge,
            Instant now, Map<String, Boolean> finished) {
        try {
            Instant at = Instant.parse(String.valueOf(row.get(timestampField)));
            String run = String.valueOf(row.get("run"));
            boolean runFinished = finished.computeIfAbsent(run, id ->
                    runs.find(new TurnRunId(id)).map(record -> record.status().isTerminal()).orElse(false));
            return Retention.expendable(at, runFinished, now, maxAge);
        } catch (RuntimeException e) {
            return false; // a row we cannot read is a row we keep
        }
    }
}
