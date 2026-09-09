package io.jclaw.storage.projection;

import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.projection.RunProjection.RunView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Materialised projections in {@code jclaw_run_projection}, one row per finished run.
 *
 * <p>Status and thread are lifted into columns so an operator can ask which runs failed without
 * decoding every body, and so a future listing endpoint has an index to use. They are copies of
 * what the body says, as everywhere else here; the body is the record.
 *
 * <p>Every failure is swallowed into a miss. This store exists to make a read cheaper, and a
 * cache that turns a database hiccup into a failed request has made the system worse.
 */
public final class JdbcRunProjectionCache implements RunProjectionCache {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunProjectionCache.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public JdbcRunProjectionCache(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<RunView> find(TurnRunId run) {
        Objects.requireNonNull(run, "run");
        try {
            List<String> bodies = jdbc.queryForList(
                    "SELECT body FROM jclaw_run_projection WHERE run = ?", String.class, run.value());
            if (bodies.isEmpty()) {
                return Optional.empty();
            }
            return RunViewCodec.decode(mapper.readValue(bodies.get(0), Map.class));
        } catch (RuntimeException e) {
            log.debug("run projection cache: read for {} failed ({})", run.value(), e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(RunView view) {
        Objects.requireNonNull(view, "view");
        Optional<TurnStatus> status = view.status().filter(TurnStatus::isTerminal);
        if (status.isEmpty()) {
            // Only a finished run's fold is final. Storing a running one would be a cache with
            // an invalidation problem, which is the thing this design exists to avoid.
            return;
        }
        String body = mapper.writeValueAsString(RunViewCodec.encode(view));
        String thread = view.scope().map(scope -> scope.thread().value()).orElse(null);
        try {
            // A run finishes once, but a resume or a retry can fold it again; delete-then-insert
            // keeps that idempotent without needing a dialect-specific upsert.
            jdbc.update("DELETE FROM jclaw_run_projection WHERE run = ?", view.run().value());
            jdbc.update("INSERT INTO jclaw_run_projection (run, thread, status, updated_at, body)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    view.run().value(), thread, status.get().name(),
                    Timestamp.from(clock.instant()), body);
        } catch (RuntimeException e) {
            log.debug("run projection cache: write for {} failed ({})", view.run().value(), e.toString());
        }
    }

    /** Rows for runs that no longer exist, removed by retention. */
    public int forgetAllExcept(List<String> keep) {
        Objects.requireNonNull(keep, "keep");
        try {
            if (keep.isEmpty()) {
                return jdbc.update("DELETE FROM jclaw_run_projection");
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(keep.size(), "?"));
            return jdbc.update("DELETE FROM jclaw_run_projection WHERE run NOT IN (" + placeholders + ")",
                    keep.toArray());
        } catch (RuntimeException e) {
            log.debug("run projection cache: prune failed ({})", e.toString());
            return 0;
        }
    }
}
