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
import org.junit.jupiter.api.Test;

/** H9: the WebSocket ticket must be short-lived, single-use, bound to one session, and not a login token. */
class WsTicketServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    private static final AppProperties PROPS = new AppProperties(null, null, null,
            new AppProperties.Auth(SECRET, Duration.ofHours(1), List.of()), null, null);

    private final WsTicketService tickets = new WsTicketService(PROPS);
    private final JwtService jwt = new JwtService(PROPS);
    private final UUID user = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    @Test
    void aFreshTicketIsRedeemedForTheUserItWasIssuedTo() {
        String t = tickets.issue(user, session);
        assertThat(tickets.redeem(t, session.toString())).contains(user);
    }

    @Test
    void aTicketWorksOnlyOnce() {
        String t = tickets.issue(user, session);
        assertThat(tickets.redeem(t, session.toString())).contains(user);
        assertThat(tickets.redeem(t, session.toString())).isEmpty();
    }

    @Test
    void aTicketIsUselessForAnotherSession() {
        String t = tickets.issue(user, session);
        assertThat(tickets.redeem(t, UUID.randomUUID().toString())).isEmpty();
        // ...and that failed attempt must not have burned it for the right session.
        assertThat(tickets.redeem(t, session.toString())).contains(user);
    }

    @Test
    void anExpiredTicketIsRefused() {
        WsTicketService shortLived = new WsTicketService(PROPS, Duration.ofSeconds(-5));
        assertThat(shortLived.redeem(shortLived.issue(user, session), session.toString())).isEmpty();
    }

    @Test
    void aTicketIsNotAcceptedAsALoginToken() {
        String t = tickets.issue(user, session);
        assertThat(jwt.verify(t)).isEmpty();
        assertThat(jwt.verifyClaims(t)).isEmpty();
    }

    @Test
    void aLoginTokenIsNotAcceptedAsATicket() {
        String login = jwt.issue(user, "a@x.com");
        assertThat(jwt.verify(login)).contains(user); // sanity: it is a valid login token
        assertThat(tickets.redeem(login, session.toString())).isEmpty();
    }

    @Test
    void tokensIssuedBeforeTheTypeIssuerAndAudienceClaimsExistedAreNoLongerAccepted() {
        String legacy = Jwts.builder().subject(user.toString()).claim("email", "a@x.com")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        // Old 24 h tokens stop working on upgrade (users sign in again); that is the point of H10.
        assertThat(jwt.verify(legacy)).isEmpty();
    }

    @Test
    void aTicketSignedWithADifferentKeyIsRefused() {
        WsTicketService other = new WsTicketService(new AppProperties(null, null, null,
                new AppProperties.Auth("a-completely-different-secret-0123456789abcdef", Duration.ofHours(1), List.of()),
                null, null));
        assertThat(tickets.redeem(other.issue(user, session), session.toString())).isEmpty();
    }

    @Test
    void tamperedMalformedAndMissingTicketsAreRefused() {
        String t = tickets.issue(user, session);
        String tampered = t.substring(0, t.length() - 3) + (t.endsWith("AAA") ? "BBB" : "AAA");
        assertThat(tickets.redeem(tampered, session.toString())).isEmpty();
        assertThat(tickets.redeem("not-a-jwt", session.toString())).isEmpty();
        assertThat(tickets.redeem("", session.toString())).isEmpty();
        assertThat(tickets.redeem(null, session.toString())).isEmpty();
        assertThat(tickets.redeem(t, null)).isEmpty();
    }

    @Test
    void theDefaultLifetimeIsShort() {
        assertThat(tickets.ttl()).isLessThanOrEqualTo(Duration.ofSeconds(60));
    }
}
