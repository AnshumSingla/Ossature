package com.ossature.interviewer.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limiter, in memory: the per-process implementation, used directly by unit tests.
 * The running application uses {@link PostgresRateLimiter} (a subclass) instead, so the limits hold
 * across every backend instance - a per-process counter would multiply each limit by the number of
 * instances and reset on every restart.
 *
 * Used to brute-force-guard {@code /api/auth/login} and to cap session creation, which
 * triggers a real paid LLM call via the Council node (PRD §8 cost notes).
 */
public class RateLimiter {

    private record Window(long windowStartMs, AtomicInteger count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** True if this call is allowed under {@code max} hits per {@code windowMs} for {@code key}. */
    public boolean allow(String key, int max, long windowMs) {
        long now = Instant.now().toEpochMilli();
        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs() >= windowMs) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        return w.count().get() <= max;
    }

    // Unbounded growth guard: a key with no recent activity is just dead weight in the map.
    void evictStale() {
        long cutoff = Instant.now().toEpochMilli() - 3_600_000;
        windows.entrySet().removeIf(e -> e.getValue().windowStartMs() < cutoff);
    }
}
