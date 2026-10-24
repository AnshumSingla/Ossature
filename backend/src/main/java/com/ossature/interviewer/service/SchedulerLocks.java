package com.ossature.interviewer.service;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A lease in Postgres ({@code scheduler_locks}) so a scheduled job runs on one backend instance at a
 * time. {@link #tryAcquire} takes the lease only if nobody holds an unexpired one; the holder releases
 * it when the job finishes, and if the holder dies the lease simply expires after {@code atMost}, so a
 * crashed instance cannot block the job forever. Uses the database clock.
 */
@Component
public class SchedulerLocks {

    private static final String ACQUIRE = """
            INSERT INTO scheduler_locks (name, locked_until, locked_by)
            VALUES (?, now() + make_interval(secs => ?), ?)
            ON CONFLICT (name) DO UPDATE
                SET locked_until = EXCLUDED.locked_until, locked_by = EXCLUDED.locked_by
                WHERE scheduler_locks.locked_until <= now()
            """;
    private static final String RELEASE = "UPDATE scheduler_locks SET locked_until = now() WHERE name = ? AND locked_by = ?";

    /** Identifies this process, so it can only release a lease it holds itself. */
    private final String holder = UUID.randomUUID().toString();
    private final JdbcTemplate jdbc;

    public SchedulerLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquire(String name, Duration atMost) {
        return jdbc.update(ACQUIRE, name, atMost.toMillis() / 1000.0, holder) == 1;
    }

    public void release(String name) {
        jdbc.update(RELEASE, name, holder);
    }
}
