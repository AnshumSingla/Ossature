package com.ossature.interviewer.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The rate limiter the application runs on: the same fixed-window rule as {@link RateLimiter}, but the
 * counters live in Postgres ({@code rate_limits}), so several backend instances share one budget and a
 * restart does not hand every attacker a fresh one.
 *
 * <p>One atomic upsert per check, using the database clock (so instances with skewed clocks agree on
 * where a window starts). It runs outside any Spring transaction on purpose: a rejected request must
 * still have been counted, which it would not be if the counter rolled back with the caller. Keys are
 * stored as SHA-256 hashes, so the table never holds an email address or IP address in the clear.
 */
@Component
public class PostgresRateLimiter extends RateLimiter {

    private static final String UPSERT = """
            INSERT INTO rate_limits (key_hash, window_start, hits) VALUES (?, now(), 1)
            ON CONFLICT (key_hash) DO UPDATE SET
                hits = CASE WHEN rate_limits.window_start <= now() - make_interval(secs => ?)
                            THEN 1 ELSE rate_limits.hits + 1 END,
                window_start = CASE WHEN rate_limits.window_start <= now() - make_interval(secs => ?)
                                    THEN now() ELSE rate_limits.window_start END
            RETURNING hits
            """;

    /** No window is longer than a day, so a counter older than that is just dead weight. */
    private static final String EVICT = "DELETE FROM rate_limits WHERE window_start < now() - interval '1 day'";

    private final JdbcTemplate jdbc;

    public PostgresRateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean allow(String key, int max, long windowMs) {
        double windowSeconds = windowMs / 1000.0;
        Integer hits = jdbc.queryForObject(UPSERT, Integer.class, hash(key), windowSeconds, windowSeconds);
        return hits != null && hits <= max;
    }

    @Scheduled(fixedDelay = 600_000)
    @Override
    void evictStale() {
        jdbc.update(EVICT);
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
