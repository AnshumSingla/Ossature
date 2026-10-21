package com.ossature.interviewer.security;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single-use tickets across all backend instances: the first INSERT of a ticket id wins, every other
 * attempt - on this instance or any other - finds the row already there.
 */
@Component
public class JdbcTicketRedemptions implements TicketRedemptions {

    private final JdbcTemplate jdbc;

    public JdbcTicketRedemptions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean firstUse(String ticketId, Instant expiresAt) {
        return jdbc.update("INSERT INTO ws_ticket_redemptions (jti, expires_at) VALUES (?, ?) ON CONFLICT (jti) DO NOTHING",
                ticketId, Timestamp.from(expiresAt)) == 1;
    }

    /** Used tickets are only remembered until they would have expired anyway. */
    @Scheduled(fixedDelay = 300_000)
    void purgeExpired() {
        jdbc.update("DELETE FROM ws_ticket_redemptions WHERE expires_at < now() - interval '1 minute'");
    }
}
