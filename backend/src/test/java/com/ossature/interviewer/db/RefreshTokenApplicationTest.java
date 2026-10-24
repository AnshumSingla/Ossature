package com.ossature.interviewer.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossature.interviewer.repo.RefreshTokenRepository;
import com.ossature.interviewer.service.AuthService;
import com.ossature.interviewer.service.RefreshTokenService;
import com.ossature.interviewer.service.RefreshTokenService.Issued;
import com.ossature.interviewer.web.dto.AuthDtos.SignupRequest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H10: refresh tokens against the real service, real transaction manager and real policies, as the
 * restricted database role. Rotation, theft detection, logout, expiry, and that the table is invisible
 * outside the auth scope.
 */
@SpringBootTest(properties = {
        "ossature.refresh.reuse-grace=1500ms",
        "ossature.refresh.session-ttl=1500ms",
})
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
class RefreshTokenApplicationTest {

    static final ScratchDatabase DB = ScratchDatabase.available() ? ScratchDatabase.create() : null;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("spring.datasource.username", () -> ScratchDatabase.APP_USER);
        registry.add("spring.datasource.password", () -> ScratchDatabase.APP_PASSWORD);
        registry.add("spring.flyway.url", DB::url);
        registry.add("spring.flyway.user", DB::ownerUser);
        registry.add("spring.flyway.password", DB::ownerPassword);
    }

    @AfterAll
    static void dropDatabase() {
        if (DB != null) {
            DB.close();
        }
    }

    @Autowired RefreshTokenService refresh;
    @Autowired RefreshTokenRepository repo;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;

    private static <T> T inAuth(Supplier<T> body) {
        return DbContext.callInScope(DbContext.SCOPE_AUTH, body);
    }

    private UUID newUser() {
        String email = "rt-" + UUID.randomUUID() + "@example.com";
        return inAuth(() -> auth.signup(new SignupRequest(email, "Passw0rd1", "rt"))).userId();
    }

    private Optional<Issued> rotate(String token) {
        return inAuth(() -> refresh.rotate(token));
    }

    private String ownerQuery(String sql) throws Exception {
        try (Connection c = DB.owner(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // ------------------------------------------------------------------ storage

    @Test
    void onlyAHashOfTheTokenIsStored() throws Exception {
        UUID user = newUser();
        Issued issued = inAuth(() -> refresh.start(user, true));
        assertThat(issued.token()).hasSizeGreaterThanOrEqualTo(43); // 256 bits, base64url
        assertThat(ownerQuery("SELECT string_agg(token_hash, ',') FROM refresh_tokens WHERE user_id = '" + user + "'"))
                .doesNotContain(issued.token()).hasSize(64); // SHA-256 hex
    }

    // ------------------------------------------------------------------ rotation

    @Test
    void usingATokenGivesTheNextOneInTheSameChainAndTheSameAbsoluteExpiry() throws Exception {
        UUID user = newUser();
        Issued first = inAuth(() -> refresh.start(user, true));
        Issued second = rotate(first.token()).orElseThrow();
        Issued third = rotate(second.token()).orElseThrow();

        assertThat(List.of(first.token(), second.token(), third.token())).doesNotHaveDuplicates();
        assertThat(second.userId()).isEqualTo(user);
        // (Postgres keeps microseconds, Java nanoseconds: compare to the millisecond.)
        var ms = java.time.temporal.ChronoUnit.MILLIS;
        assertThat(third.expiresAt().truncatedTo(ms)).as("rotation must not extend the login's lifetime")
                .isEqualTo(first.expiresAt().truncatedTo(ms));
        assertThat(second.expiresAt().truncatedTo(ms)).isEqualTo(first.expiresAt().truncatedTo(ms));
        assertThat(ownerQuery("SELECT count(DISTINCT family_id) FROM refresh_tokens WHERE user_id = '" + user + "'")).isEqualTo("1");
    }

    @Test
    void unknownBlankOrGarbageTokensGetNothing() {
        assertThat(rotate("not-a-token")).isEmpty();
        assertThat(rotate("")).isEmpty();
        assertThat(rotate(null)).isEmpty();
    }

    // ------------------------------------------------------------------ theft detection

    @Test
    void aTokenUsedAgainAfterTheGraceWindowRevokesTheWholeLogin() throws Exception {
        UUID user = newUser();
        Issued stolen = inAuth(() -> refresh.start(user, true));
        Issued legitimateNext = rotate(stolen.token()).orElseThrow(); // the real user refreshes

        Thread.sleep(1700); // past the grace window: this is no longer two tabs racing
        assertThat(rotate(stolen.token())).as("the attacker replays the old token").isEmpty();

        assertThat(rotate(legitimateNext.token())).as("...and the whole chain is now revoked").isEmpty();
        assertThat(ownerQuery("SELECT count(*) FROM refresh_tokens WHERE user_id = '" + user + "' AND revoked_at IS NULL")).isEqualTo("0");
    }

    @Test
    void twoTabsRefreshingAtTheSameTimeBothGetASessionAndNobodyIsLoggedOut() throws Exception {
        UUID user = newUser();
        Issued shared = inAuth(() -> refresh.start(user, true));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<Issued>>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return rotate(shared.token());
            }));
        }
        go.countDown();
        Issued a = results.get(0).get().orElseThrow();
        Issued b = results.get(1).get().orElseThrow();
        pool.shutdown();

        assertThat(a.token()).isNotEqualTo(b.token());
        assertThat(rotate(a.token())).as("neither tab was logged out").isPresent();
        assertThat(rotate(b.token())).isPresent();
    }

    // ------------------------------------------------------------------ logout and expiry

    @Test
    void logoutEndsTheLoginSoNoTokenInTheChainWorksAnyMore() {
        UUID user = newUser();
        Issued first = inAuth(() -> refresh.start(user, true));
        Issued current = rotate(first.token()).orElseThrow();

        inAuth(() -> {
            refresh.end(current.token());
            return null;
        });
        assertThat(rotate(current.token())).isEmpty();
        assertThat(rotate(first.token())).isEmpty();
    }

    @Test
    void endingAnUnknownTokenIsHarmless() {
        inAuth(() -> {
            refresh.end("nonexistent");
            refresh.end(null);
            return null;
        });
    }

    @Test
    void aLoginThatWasNotRememberedExpiresOnItsOwn() throws Exception {
        UUID user = newUser();
        Issued shortLived = inAuth(() -> refresh.start(user, false));
        assertThat(shortLived.persistent()).isFalse();
        Thread.sleep(1700); // session-ttl is 1.5 s in this test
        assertThat(rotate(shortLived.token())).isEmpty();
    }

    @Test
    void aRememberedLoginLastsLong() {
        UUID user = newUser();
        Issued remembered = inAuth(() -> refresh.start(user, true));
        assertThat(remembered.expiresAt()).isAfter(Instant.now().plusSeconds(29L * 24 * 3600));
    }

    @Test
    void expiredSessionsArePurged() throws Exception {
        UUID user = newUser();
        Issued old = inAuth(() -> refresh.start(user, true));
        try (Connection c = DB.owner(); Statement s = c.createStatement()) {
            s.execute("UPDATE refresh_tokens SET expires_at = now() - interval '3 days' WHERE user_id = '" + user + "'");
        }
        inAuth(() -> repo.deleteExpiredBefore(Instant.now().minusSeconds(86_400)));
        assertThat(ownerQuery("SELECT count(*) FROM refresh_tokens WHERE user_id = '" + user + "'")).isEqualTo("0");
        assertThat(rotate(old.token())).isEmpty();
    }

    // ------------------------------------------------------------------ row-level security

    private int visibleRefreshRows() {
        Integer n = new TransactionTemplate(tm).execute(s -> jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class));
        return n == null ? 0 : n;
    }

    @Test
    void theTableIsInvisibleOutsideTheAuthScope() {
        UUID user = newUser();
        inAuth(() -> refresh.start(user, true));

        // no identity at all, and a signed-in user (even the owner of those very tokens): nothing
        assertThat(visibleRefreshRows()).isZero();
        assertThat(DbContext.callAs(user, false, this::visibleRefreshRows)).isZero();
        assertThat(DbContext.callAs(user, true, this::visibleRefreshRows)).isZero();
        assertThat(DbContext.callInScope(DbContext.SCOPE_SCHEDULER, this::visibleRefreshRows)).isZero();
        // ...while the auth scope sees them
        assertThat(inAuth(this::visibleRefreshRows)).isPositive();
    }
}
