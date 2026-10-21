package com.ossature.interviewer.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.security.JdbcTicketRedemptions;
import com.ossature.interviewer.security.WsTicketService;
import com.ossature.interviewer.service.PostgresRateLimiter;
import com.ossature.interviewer.service.SchedulerLocks;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A1.5 - A1.7: state that several backend instances must share (rate-limit budgets, the scheduler
 * lease, used WebSocket tickets). Each "instance" here is a separate object with its own JDBC
 * connections, talking only through Postgres - the only thing real instances share - and the
 * concurrency tests run them in parallel threads. Runs as the restricted application role.
 */
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
class MultiInstanceStateTest {

    static final ScratchDatabase DB = ScratchDatabase.available() ? ScratchDatabase.create() : null;
    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    static final AppProperties PROPS = new AppProperties(null, null, null,
            new AppProperties.Auth(SECRET, Duration.ofHours(1), List.of()), null, null);

    @AfterAll
    static void drop() {
        if (DB != null) {
            DB.close();
        }
    }

    /** A new JDBC pool as the application role: what one more backend instance would have. */
    private static JdbcTemplate instanceConnection() {
        return new JdbcTemplate(new DriverManagerDataSource(DB.url(), ScratchDatabase.APP_USER, ScratchDatabase.APP_PASSWORD));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static <T> List<T> inParallel(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    // ------------------------------------------------------------------ A1.5 shared rate limiter

    @Test
    void theLimitIsEnforcedPerKey() {
        var limiter = new PostgresRateLimiter(instanceConnection());
        String key = unique("login:email");
        assertThat(limiter.allow(key, 3, 60_000)).isTrue();
        assertThat(limiter.allow(key, 3, 60_000)).isTrue();
        assertThat(limiter.allow(key, 3, 60_000)).isTrue();
        assertThat(limiter.allow(key, 3, 60_000)).isFalse();
        assertThat(limiter.allow(key, 3, 60_000)).isFalse();
        assertThat(limiter.allow(unique("login:email"), 3, 60_000)).as("another key has its own budget").isTrue();
    }

    @Test
    void twoInstancesShareOneBudget() {
        var a = new PostgresRateLimiter(instanceConnection());
        var b = new PostgresRateLimiter(instanceConnection());
        String key = unique("signup:ip");
        int allowed = 0;
        for (int i = 0; i < 4; i++) {
            allowed += a.allow(key, 5, 60_000) ? 1 : 0;
        }
        for (int i = 0; i < 4; i++) {
            allowed += b.allow(key, 5, 60_000) ? 1 : 0;
        }
        assertThat(allowed).as("5 allowed in total, not 5 per instance").isEqualTo(5);
    }

    @Test
    void aBurstAcrossInstancesAllowsExactlyTheLimit() throws Exception {
        var instances = List.of(new PostgresRateLimiter(instanceConnection()), new PostgresRateLimiter(instanceConnection()),
                new PostgresRateLimiter(instanceConnection()));
        String key = unique("login:pair");
        AtomicInteger turn = new AtomicInteger();
        List<Integer> allowedPerThread = inParallel(9, () -> {
            var limiter = instances.get(turn.getAndIncrement() % instances.size());
            int ok = 0;
            for (int i = 0; i < 10; i++) {
                ok += limiter.allow(key, 25, 60_000) ? 1 : 0;
            }
            return ok;
        });
        assertThat(allowedPerThread.stream().mapToInt(Integer::intValue).sum()).isEqualTo(25);
    }

    @Test
    void theWindowResetsAfterItElapses() throws Exception {
        var limiter = new PostgresRateLimiter(instanceConnection());
        String key = unique("window");
        assertThat(limiter.allow(key, 1, 400)).isTrue();
        assertThat(limiter.allow(key, 1, 400)).isFalse();
        Thread.sleep(600);
        assertThat(limiter.allow(key, 1, 400)).as("a new window").isTrue();
    }

    @Test
    void theTableNeverHoldsAnEmailOrAnAddressInTheClear() throws Exception {
        var limiter = new PostgresRateLimiter(instanceConnection());
        String email = unique("victim") + "@example.com";
        limiter.allow("login:pair:" + email + "|203.0.113.9", 5, 60_000);
        try (Connection c = DB.owner(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT string_agg(key_hash, ',') FROM rate_limits")) {
            rs.next();
            assertThat(rs.getString(1)).doesNotContain("victim").doesNotContain("203.0.113.9").doesNotContain("@");
        }
    }

    // ------------------------------------------------------------------ A1.6 scheduler single-runner

    @Test
    void onlyOneInstanceHoldsTheLeaseAtATime() {
        var a = new SchedulerLocks(instanceConnection());
        var b = new SchedulerLocks(instanceConnection());
        String job = unique("job");
        assertThat(a.tryAcquire(job, Duration.ofMinutes(5))).isTrue();
        assertThat(b.tryAcquire(job, Duration.ofMinutes(5))).isFalse();
        assertThat(a.tryAcquire(job, Duration.ofMinutes(5))).as("not re-entrant: a running sweep is not restarted").isFalse();
        a.release(job);
        assertThat(b.tryAcquire(job, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void anotherInstanceCannotReleaseMyLease() {
        var a = new SchedulerLocks(instanceConnection());
        var b = new SchedulerLocks(instanceConnection());
        String job = unique("job");
        assertThat(a.tryAcquire(job, Duration.ofMinutes(5))).isTrue();
        b.release(job);
        assertThat(b.tryAcquire(job, Duration.ofMinutes(5))).as("still held by a").isFalse();
    }

    @Test
    void aCrashedHoldersLeaseExpires() throws Exception {
        var crashed = new SchedulerLocks(instanceConnection());
        var survivor = new SchedulerLocks(instanceConnection());
        String job = unique("job");
        assertThat(crashed.tryAcquire(job, Duration.ofMillis(300))).isTrue(); // never released
        assertThat(survivor.tryAcquire(job, Duration.ofMinutes(5))).isFalse();
        Thread.sleep(500);
        assertThat(survivor.tryAcquire(job, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void ofManyInstancesStartingTogetherExactlyOneWins() throws Exception {
        String job = unique("job");
        List<SchedulerLocks> instances = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            instances.add(new SchedulerLocks(instanceConnection()));
        }
        AtomicInteger next = new AtomicInteger();
        List<Boolean> results = inParallel(10, () -> instances.get(next.getAndIncrement()).tryAcquire(job, Duration.ofMinutes(5)));
        assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(1);
    }

    // ------------------------------------------------------------------ A1.7 single-use tickets across instances

    @Test
    void aTicketIssuedOnOneInstanceWorksOnceOnAnyInstance() {
        var a = new WsTicketService(PROPS, Duration.ofSeconds(30), new JdbcTicketRedemptions(instanceConnection()));
        var b = new WsTicketService(PROPS, Duration.ofSeconds(30), new JdbcTicketRedemptions(instanceConnection()));
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        String ticket = a.issue(user, session);
        assertThat(b.redeem(ticket, session.toString())).as("another instance accepts it").contains(user);
        assertThat(a.redeem(ticket, session.toString())).as("...and then no instance does").isEmpty();
        assertThat(b.redeem(ticket, session.toString())).isEmpty();
    }

    @Test
    void aTicketReplayedAtManyInstancesAtOnceIsAcceptedExactlyOnce() throws Exception {
        List<WsTicketService> instances = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            instances.add(new WsTicketService(PROPS, Duration.ofSeconds(30), new JdbcTicketRedemptions(instanceConnection())));
        }
        UUID session = UUID.randomUUID();
        String ticket = instances.get(0).issue(UUID.randomUUID(), session);
        AtomicInteger next = new AtomicInteger();
        List<Boolean> accepted = inParallel(8, () -> instances.get(next.getAndIncrement()).redeem(ticket, session.toString()).isPresent());
        assertThat(accepted.stream().filter(Boolean::booleanValue)).hasSize(1);
    }

    // ------------------------------------------------------------------ privileges

    @Test
    void theAgentRoleHasNoAccessToTheOperationalTables() throws SQLException {
        try (Connection c = DB.agent(); Statement s = c.createStatement()) {
            for (String table : List.of("rate_limits", "scheduler_locks", "ws_ticket_redemptions")) {
                org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM " + table));
            }
        }
    }
}
