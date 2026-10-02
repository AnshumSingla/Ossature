package com.ossature.interviewer.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter, in-memory. Fine for the current single-instance deployment
 * (same caveat as {@code agent/app/store.py}'s in-memory session cache) - a real
 * multi-instance setup would need this backed by something shared (e.g. Redis) instead.
 *
 * Used to brute-force-guard {@code /api/auth/login} and to cap session creation, which
 * triggers a real paid LLM call via the Council node (PRD §8 cost notes).
 */
@Component
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
    @Scheduled(fixedDelay = 600_000)
    void evictStale() {
        long cutoff = Instant.now().toEpochMilli() - 3_600_000;
        windows.entrySet().removeIf(e -> e.getValue().windowStartMs() < cutoff);
    }
}
