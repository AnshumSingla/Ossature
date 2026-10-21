package com.ossature.interviewer.security;

import com.ossature.interviewer.config.AppProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One-time tickets for opening the interview WebSocket (H9).
 *
 * <p>A browser can't set an {@code Authorization} header on a WebSocket handshake, so the old code
 * put the user's real login token in the URL ({@code ?token=...}), where it ends up in proxy and
 * server logs, browser history and Referer headers. Instead the client now asks (over normal
 * authenticated REST) for a ticket that is:
 * <ul>
 *   <li><b>short-lived</b> - 30 seconds, enough to open the socket and nothing more;</li>
 *   <li><b>bound to one session and one user</b> - useless for any other session;</li>
 *   <li><b>single-use</b> - a second handshake with the same ticket is refused;</li>
 *   <li><b>not a login token</b> - it carries {@code typ=ws}, which {@link JwtService} rejects, so a
 *       ticket that leaks from a URL can't call the REST API.</li>
 * </ul>
 * Verification is stateless (signature + expiry); "already used" is the only shared state, kept in
 * Postgres ({@link JdbcTicketRedemptions}) so a ticket is single-use across every backend instance.
 */
@Component
public class WsTicketService {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    static final String TYPE = "ws";

    private final SecretKey key;
    private final Duration ttl;
    private final TicketRedemptions redemptions;

    /** Falls back to a per-process store only when no shared one exists (unit tests, no database). */
    @Autowired
    public WsTicketService(AppProperties props, ObjectProvider<TicketRedemptions> redemptions) {
        this(props, DEFAULT_TTL, redemptions.getIfAvailable(InMemoryTicketRedemptions::new));
    }

    WsTicketService(AppProperties props) {
        this(props, DEFAULT_TTL);
    }

    WsTicketService(AppProperties props, Duration ttl) {
        this(props, ttl, new InMemoryTicketRedemptions());
    }

    public WsTicketService(AppProperties props, Duration ttl, TicketRedemptions redemptions) {
        this.key = Keys.hmacShaKeyFor(props.auth().jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
        this.redemptions = redemptions;
    }

    public Duration ttl() {
        return ttl;
    }

    /** A ticket letting {@code userId} open the socket for {@code sessionId}, once, soon. */
    public String issue(UUID userId, UUID sessionId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("typ", TYPE)
                .claim("sid", sessionId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Consumes the ticket. Returns the user it was issued to, or empty if it is malformed, mis-signed,
     * expired, not a ticket, for a different session, or already used.
     */
    public Optional<UUID> redeem(String ticket, String sessionId) {
        if (ticket == null || ticket.isBlank() || sessionId == null) {
            return Optional.empty();
        }
        try {
            var payload = Jwts.parser().verifyWith(key).build().parseSignedClaims(ticket).getPayload();
            if (!TYPE.equals(payload.get("typ", String.class))
                    || !sessionId.equals(payload.get("sid", String.class))
                    || payload.getId() == null) {
                return Optional.empty();
            }
            if (!redemptions.firstUse(payload.getId(), payload.getExpiration().toInstant())) {
                return Optional.empty(); // already used
            }
            return Optional.of(UUID.fromString(payload.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
