package io.jclaw.storage.lock;

import io.jclaw.contracts.turn.ThreadLock;
import io.jclaw.contracts.turn.TurnScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link ThreadLock} over a row in {@code jclaw_thread_locks}, so the exclusion holds across
 * hosts rather than across processes on one machine.
 *
 * <p>{@link FileThreadLock} is correct and cheaper, and remains the default: an OS file lock is
 * atomic at the kernel and vanishes the instant its process dies, so nothing has to guess whether
 * a silent holder is slow or gone. What it cannot do is span machines — the lock is invisible to
 * another host, and on a shared filesystem it is not reliable at all. This is the version for a
 * deployment with two workers and one database.
 *
 * <h2>The row is a lease, and that is the cost</h2>
 *
 * <p>A row cannot vanish when a process dies, so it carries an expiry and the holder renews it.
 * That reintroduces exactly the reasoning the file lock avoided, and it is worth being precise
 * about what it does and does not guarantee.
 *
 * <p>It <strong>does</strong> guarantee that two live, healthy hosts never both hold one thread:
 * acquisition is a primary-key insert, which the database decides atomically, and a reap of an
 * expired row is owner-scoped so it can never delete a lock somebody has since taken.
 *
 * <p>It does <strong>not</strong> fence a host that freezes. A long garbage-collection pause, a
 * suspended VM, or a network partition lasting past the TTL lets another host take the thread;
 * if the frozen host then resumes and writes, two runs can interleave one transcript — the very
 * thing the lock exists to prevent. Closing that needs a fence token threaded through every
 * durable write, which is a larger change than this and is not implemented. What this buys over
 * today is still real: without it a second host has no exclusion at all, not a weak one.
 *
 * <p>The renewal interval is a third of the TTL, so two consecutive missed renewals are survived.
 * One daemon thread renews every lock this instance holds; a lock whose renewal fails is left to
 * expire rather than retried forever, because a holder that cannot reach the database cannot
 * safely believe it still owns anything.
 */
public final class SqlThreadLock implements ThreadLock, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqlThreadLock.class);

    /** Long enough to survive a slow turn, short enough that a dead host frees a thread. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final String owner;
    private final Duration ttl;
    private final Clock clock;
    private final ScheduledExecutorService renewer;
    private final Map<String, Boolean> held = new ConcurrentHashMap<>();

    /**
     * @param owner a value unique to this process — the worker id. Two processes sharing one
     *              owner string would each be able to release the other's lock
     */
    public SqlThreadLock(DataSource dataSource, String owner, Clock clock) {
        this(dataSource, owner, DEFAULT_TTL, clock);
    }

    public SqlThreadLock(DataSource dataSource, String owner, Duration ttl, Clock clock) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("the lock ttl must be positive");
        }
        this.renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jclaw-thread-lock-renewer");
            thread.setDaemon(true);
            return thread;
        });
        long everyMillis = Math.max(1_000, ttl.toMillis() / 3);
        renewer.scheduleWithFixedDelay(this::renewAll, everyMillis, everyMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<Held> tryAcquire(TurnScope scope) {
        Objects.requireNonNull(scope, "scope");
        String key = scope.lockKey();
        Instant now = clock.instant();

        // Reap a holder whose lease ran out. Scoped to the expiry, so a live lock is untouched;
        // if two acquirers race here, both may delete and both may insert, and the primary key
        // is what makes exactly one of them win.
        try {
            jdbc.update("DELETE FROM jclaw_thread_locks WHERE scope = ? AND expires_at < ?",
                    key, Timestamp.from(now));
        } catch (DataAccessException e) {
            log.debug("thread lock: could not reap {} ({})", key, e.getMessage());
            return Optional.empty();
        }

        try {
            jdbc.update("INSERT INTO jclaw_thread_locks (scope, owner, acquired_at, expires_at)"
                            + " VALUES (?, ?, ?, ?)",
                    key, owner, Timestamp.from(now), Timestamp.from(now.plus(ttl)));
        } catch (DataAccessException e) {
            // A primary-key violation is the ordinary answer, not an error: somebody holds it.
            log.debug("thread lock: {} is held by another run", key);
            return Optional.empty();
        }
        held.put(key, Boolean.TRUE);
        log.debug("thread lock: acquired {} for {} ({}s)", key, owner, ttl.toSeconds());
        return Optional.of(new SqlHeld(scope, key));
    }

    /** Locks this instance currently holds, for diagnostics and tests. */
    public int heldCount() {
        return held.size();
    }

    private void release(String key) {
        if (held.remove(key) == null) {
            return;
        }
        try {
            // Owner-scoped: if our lease expired and somebody else took the thread, this deletes
            // nothing rather than releasing a lock we no longer own.
            jdbc.update("DELETE FROM jclaw_thread_locks WHERE scope = ? AND owner = ?", key, owner);
            log.debug("thread lock: released {}", key);
        } catch (DataAccessException e) {
            log.debug("thread lock: could not release {} ({}); it will expire", key, e.getMessage());
        }
    }

    private void renewAll() {
        Instant now = clock.instant();
        for (String key : held.keySet()) {
            try {
                int rows = jdbc.update(
                        "UPDATE jclaw_thread_locks SET expires_at = ? WHERE scope = ? AND owner = ?",
                        Timestamp.from(now.plus(ttl)), key, owner);
                if (rows == 0) {
                    // The row is gone or belongs to someone else: our lease lapsed. Stop claiming
                    // it, so a later release cannot delete a lock another host now holds.
                    held.remove(key);
                    log.debug("thread lock: lost {} — the lease expired before it was renewed", key);
                }
            } catch (DataAccessException e) {
                log.debug("thread lock: renewal of {} failed ({})", key, e.getMessage());
            }
        }
    }

    /** Stops the renewer. Held locks are left to expire, as they would if the process died. */
    @Override
    public void close() {
        renewer.shutdownNow();
    }

    private final class SqlHeld implements Held {
        private final TurnScope scope;
        private final String key;

        private SqlHeld(TurnScope scope, String key) {
            this.scope = scope;
            this.key = key;
        }

        @Override
        public TurnScope scope() {
            return scope;
        }

        @Override
        public void close() {
            release(key);
        }
    }
}
