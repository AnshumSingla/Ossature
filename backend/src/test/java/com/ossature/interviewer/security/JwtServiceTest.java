package com.ossature.interviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossature.interviewer.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/** H10: the access token is short-lived, has an issuer and audience, and only tokens with both are accepted. */
class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    private static final AppProperties PROPS = new AppProperties(null, null, null,
            new AppProperties.Auth(SECRET, Duration.ofMinutes(15), List.of()), null, null);
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtService jwt = new JwtService(PROPS);
    private final UUID user = UUID.randomUUID();

    private static String forged(java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> customize) {
        var b = Jwts.builder().subject(UUID.randomUUID().toString()).claim("email", "a@x.com")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000));
        customize.accept(b);
        return b.signWith(KEY).compact();
    }

    @Test
    void anIssuedTokenIsAcceptedAndExpiresInTheConfiguredFifteenMinutes() {
        String token = jwt.issue(user, "a@x.com");
        assertThat(jwt.verify(token)).contains(user);
        assertThat(jwt.expirySeconds()).isEqualTo(900);

        var payload = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token).getPayload();
        assertThat(payload.getIssuer()).isEqualTo("ossature-backend");
        assertThat(payload.getAudience()).containsExactly("ossature-api");
        assertThat(payload.getExpiration().getTime() - payload.getIssuedAt().getTime()).isBetween(899_000L, 901_000L);
    }

    @Test
    void aTokenWithoutTheAudienceIsRefused() {
        // e.g. a token from before issuer/audience were added, or minted by something else with the same key
        assertThat(jwt.verify(forged(b -> b.claim("typ", "access").issuer("ossature-backend")))).isEmpty();
    }

    @Test
    void aTokenForAnotherAudienceOrIssuerIsRefused() {
        assertThat(jwt.verify(forged(b -> b.claim("typ", "access").issuer("ossature-backend").audience().add("someone-else").and()))).isEmpty();
        assertThat(jwt.verify(forged(b -> b.claim("typ", "access").issuer("evil").audience().add("ossature-api").and()))).isEmpty();
    }

    @Test
    void aTokenWithNoTypeIsNoLongerAnAccessToken() {
        assertThat(jwt.verify(forged(b -> b.issuer("ossature-backend").audience().add("ossature-api").and()))).isEmpty();
    }

    @Test
    void aWebSocketTicketIsNotAnAccessTokenAndAnAccessTokenIsNotATicket() {
        var tickets = new WsTicketService(PROPS);
        UUID session = UUID.randomUUID();
        assertThat(jwt.verify(tickets.issue(user, session))).isEmpty();
        assertThat(tickets.redeem(jwt.issue(user, "a@x.com"), session.toString())).isEmpty();
    }

    @Test
    void anExpiredOrBadlySignedTokenIsRefused() {
        assertThat(jwt.verify(forged(b -> b.claim("typ", "access").issuer("ossature-backend").audience().add("ossature-api").and()
                .expiration(new Date(System.currentTimeMillis() - 1000))))).isEmpty();
        var otherKey = Keys.hmacShaKeyFor("another-secret-another-secret-another-secret-9".getBytes(StandardCharsets.UTF_8));
        String foreign = Jwts.builder().subject(user.toString()).claim("typ", "access").issuer("ossature-backend")
                .audience().add("ossature-api").and().expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(otherKey).compact();
        assertThat(jwt.verify(foreign)).isEmpty();
    }
}
