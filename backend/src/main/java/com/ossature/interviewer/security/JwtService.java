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

/**
 * Issues and verifies the short-lived access token (PRD 6.2 auth follow-up, H10). It is a bearer token
 * the browser keeps in memory only; staying signed in is the refresh token's job (RefreshTokenService).
 * Tokens carry an issuer and an audience, and only tokens with both are accepted, so a token minted for
 * another purpose or another service (including this service's own WebSocket tickets) is refused.
 */
@Component
public class JwtService {

    public static final String ISSUER = "ossature-backend";
    public static final String AUDIENCE = "ossature-api";
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
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(userId.toString())
                .claim("email", email)
                .claim("typ", ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expiryMillis)))
                .signWith(key)
                .compact();
    }

    /** How long a token issued now stays valid, for the client to schedule its refresh. */
    public long expirySeconds() {
        return expiryMillis / 1000;
    }

    /** Empty when the token is missing, malformed, expired, or mis-signed. */
    public Optional<UUID> verify(String token) {
        return verifyClaims(token).map(Claims::userId);
    }

    /** Same validity rules as {@link #verify}, but also exposes the email claim. */
    public Optional<Claims> verifyClaims(String token) {
        try {
            var payload = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).requireAudience(AUDIENCE)
                    .build().parseSignedClaims(token).getPayload();
            if (!ACCESS.equals(payload.get("typ", String.class))) {
                return Optional.empty(); // e.g. a WebSocket ticket must never authenticate REST calls
            }
            return Optional.of(new Claims(UUID.fromString(payload.getSubject()), payload.get("email", String.class)));
        } catch (RuntimeException e) { // JwtException, a missing or non-UUID subject, anything malformed: not a valid token
            return Optional.empty();
        }
    }

    public record Claims(UUID userId, String email) {}
}
