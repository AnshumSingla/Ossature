package com.ossature.interviewer.security;

import com.ossature.interviewer.config.AppProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** Issues and verifies the stateless session token (PRD 6.2 auth follow-up). */
@Component
public class JwtService {

    /** Tokens issued before the type claim existed have none and count as access tokens. */
    private static final String ACCESS = "access";

    private final SecretKey key;
    private final long expiryMillis;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.auth().jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expiryMillis = props.auth().jwtExpiry().toMillis();
    }

    public String issue(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("typ", ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expiryMillis)))
                .signWith(key)
                .compact();
    }

    /** Empty when the token is missing, malformed, expired, or mis-signed. */
    public Optional<UUID> verify(String token) {
        return verifyClaims(token).map(Claims::userId);
    }

    /** Same validity rules as {@link #verify}, but also exposes the email claim. */
    public Optional<Claims> verifyClaims(String token) {
        try {
            var payload = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String type = payload.get("typ", String.class);
            if (type != null && !ACCESS.equals(type)) {
                return Optional.empty(); // e.g. a WebSocket ticket must never authenticate REST calls
            }
            return Optional.of(new Claims(UUID.fromString(payload.getSubject()), payload.get("email", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record Claims(UUID userId, String email) {}
}
