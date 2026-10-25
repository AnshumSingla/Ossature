package com.ossature.interviewer.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process fallback (unit tests, no database). The application uses {@link JdbcTicketRedemptions}. */
public class InMemoryTicketRedemptions implements TicketRedemptions {

    private final Map<String, Long> redeemed = new ConcurrentHashMap<>();

    @Override
    public boolean firstUse(String ticketId, Instant expiresAt) {
        long now = System.currentTimeMillis();
        redeemed.values().removeIf(expiry -> expiry < now);
        return redeemed.putIfAbsent(ticketId, expiresAt.toEpochMilli()) == null;
    }
}
