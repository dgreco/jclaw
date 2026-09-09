package io.jclaw.storage.lock;

import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.ThreadLock;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.storage.sql.SqlSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two hosts, one database. Each {@link SqlThreadLock} instance with its own owner id stands in
 * for a separate worker process: that is what the lock actually distinguishes, and it is the
 * thing {@link FileThreadLock} cannot do at all.
 */
class SqlThreadLockTest {

    private static final TurnScope SCOPE = TurnScope.local("p", new ThreadId("shared"));

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    /** One database both "hosts" share, as a deployment would. */
    private static DataSource database() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        SqlSchema.migrate(ds);
        return ds;
    }

    private SqlThreadLock host(DataSource ds, String owner) {
        return new SqlThreadLock(ds, owner, Duration.ofMinutes(5), clock);
    }

    @Test
    @DisplayName("one host holds a thread and the other is refused, until it is released")
    void exclusionSpansHosts() {
        DataSource ds = database();
        try (SqlThreadLock alpha = host(ds, "host-a"); SqlThreadLock beta = host(ds, "host-b")) {
            Optional<ThreadLock.Held> first = alpha.tryAcquire(SCOPE);
            assertTrue(first.isPresent(), "the first host takes the thread");
            assertTrue(beta.tryAcquire(SCOPE).isEmpty(),
                    "the second host sees it — which a file lock on another machine would not");
            assertTrue(alpha.tryAcquire(SCOPE).isEmpty(), "and so does the holder itself");

            first.get().close();
            Optional<ThreadLock.Held> second = beta.tryAcquire(SCOPE);
            assertTrue(second.isPresent(), "released, the other host may take it");
            second.get().close();
        }
    }

    @Test
    @DisplayName("a different thread is never contended")
    void differentThreadsAreIndependent() {
        DataSource ds = database();
        try (SqlThreadLock alpha = host(ds, "host-a"); SqlThreadLock beta = host(ds, "host-b")) {
            assertTrue(alpha.tryAcquire(SCOPE).isPresent());
            assertTrue(beta.tryAcquire(TurnScope.local("p", new ThreadId("other"))).isPresent());
            assertTrue(beta.tryAcquire(TurnScope.local("other-project", new ThreadId("shared"))).isPresent(),
                    "the whole scope is the key, not just the thread name");
        }
    }

    @Test
    @DisplayName("a dead host's lease expires and the thread becomes takeable")
    void anExpiredLeaseIsReaped() {
        DataSource ds = database();
        try (SqlThreadLock alpha = host(ds, "host-a"); SqlThreadLock beta = host(ds, "host-b")) {
            assertTrue(alpha.tryAcquire(SCOPE).isPresent());
            assertTrue(beta.tryAcquire(SCOPE).isEmpty());

            // host-a dies without releasing: nothing renews the row.
            now.set(now.get().plus(Duration.ofMinutes(6)));
            assertTrue(beta.tryAcquire(SCOPE).isPresent(),
                    "a row cannot vanish with the process, so the lease is what frees the thread");
        }
    }

    @Test
    @DisplayName("releasing after losing the lease does not free the new holder's lock")
    void releaseIsOwnerScoped() {
        DataSource ds = database();
        try (SqlThreadLock alpha = host(ds, "host-a"); SqlThreadLock beta = host(ds, "host-b")) {
            ThreadLock.Held stale = alpha.tryAcquire(SCOPE).orElseThrow();
            now.set(now.get().plus(Duration.ofMinutes(6)));
            ThreadLock.Held fresh = beta.tryAcquire(SCOPE).orElseThrow();

            // The first host wakes up and tidies up. Its DELETE is scoped to its own owner, so it
            // removes nothing — otherwise a slow host would hand the thread to a third party.
            stale.close();
            assertTrue(alpha.tryAcquire(SCOPE).isEmpty(),
                    "host-b still holds it after host-a released what it no longer owned");
            fresh.close();
            assertTrue(alpha.tryAcquire(SCOPE).isPresent());
        }
    }

    @Test
    @DisplayName("closing twice is harmless, and a closed handle frees the thread once")
    void closingTwiceIsHarmless() {
        DataSource ds = database();
        try (SqlThreadLock alpha = host(ds, "host-a")) {
            ThreadLock.Held held = alpha.tryAcquire(SCOPE).orElseThrow();
            held.close();
            held.close();
            assertEquals(0, alpha.heldCount());
            assertTrue(alpha.tryAcquire(SCOPE).isPresent());
        }
    }

    @Test
    @DisplayName("many hosts racing for one thread produce exactly one winner")
    void concurrentAcquisitionHasOneWinner() throws Exception {
        DataSource ds = database();
        int hosts = 8;
        CountDownLatch ready = new CountDownLatch(hosts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(hosts)) {
            for (int i = 0; i < hosts; i++) {
                String owner = "host-" + i;
                pool.submit(() -> {
                    try (SqlThreadLock lock = host(ds, owner)) {
                        ready.countDown();
                        go.await();
                        if (lock.tryAcquire(SCOPE).isPresent()) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
        }
        assertEquals(1, winners.get(),
                "acquisition is a primary-key insert, so the database decides the race");
        assertEquals(Integer.valueOf(1), new JdbcTemplate(ds).queryForObject(
                "SELECT COUNT(*) FROM jclaw_thread_locks", Integer.class));
    }

    @Test
    @DisplayName("a lock is a row, so it is visible to anything that can read the database")
    void theLockIsInspectable() {
        DataSource ds = database();
        try (SqlThreadLock alpha = host(ds, "host-a")) {
            alpha.tryAcquire(SCOPE).orElseThrow();
            assertEquals("host-a", new JdbcTemplate(ds).queryForObject(
                    "SELECT owner FROM jclaw_thread_locks WHERE scope = ?", String.class, SCOPE.lockKey()),
                    "an operator can see which host holds a thread, which a file lock never showed");
            assertFalse(SCOPE.lockKey().isBlank());
        }
    }
}
