package com.ossature.interviewer.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossature.interviewer.config.RetentionProperties;
import com.ossature.interviewer.repo.InterviewSessionRepository;
import com.ossature.interviewer.service.AccountService;
import com.ossature.interviewer.service.AuthService;
import com.ossature.interviewer.service.EventLogService;
import com.ossature.interviewer.service.QuestionBankService;
import com.ossature.interviewer.service.RefreshTokenService;
import com.ossature.interviewer.service.RetentionJob;
import com.ossature.interviewer.service.SessionService;
import com.ossature.interviewer.service.WrongPasswordException;
import com.ossature.interviewer.web.dto.AuthDtos.SignupRequest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * H21: deleting a session, deleting an account, downloading your data, and the retention purge - the real
 * services against a real database as the restricted role, so row-level security is part of what is tested.
 */
@SpringBootTest
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
class DataDeletionApplicationTest {

    static final ScratchDatabase DB = ScratchDatabase.available() ? ScratchDatabase.create() : null;
    static final String PASSWORD = "Passw0rd1";

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

    @Autowired AuthService auth;
    @Autowired SessionService sessions;
    @Autowired EventLogService eventLog;
    @Autowired QuestionBankService bank;
    @Autowired AccountService accounts;
    @Autowired RefreshTokenService refresh;
    @Autowired RetentionJob retention;
    @Autowired InterviewSessionRepository sessionRepo;

    // ------------------------------------------------------------------ helpers

    private static <T> T inAuth(Supplier<T> body) {
        return DbContext.callInScope(DbContext.SCOPE_AUTH, body);
    }

    private UUID newUser(String tag) {
        String email = tag + "-" + UUID.randomUUID() + "@example.com";
        UUID id = inAuth(() -> auth.signup(new SignupRequest(email, PASSWORD, tag))).userId();
        inAuth(() -> refresh.start(id, true));
        return id;
    }

    private UUID approvedQuestion() {
        return bank.listApproved().get(0).getId();
    }

    /** A session for {@code user} with one private chat message and one summary. */
    private UUID sessionWithData(UUID user, String secret) throws Exception {
        UUID sid = DbContext.callAs(user, false, () -> sessions.create(user, approvedQuestion())).getId();
        DbContext.runAs(user, false, () -> eventLog.append(sid, "chat.message", Map.of("sender", "candidate", "text", secret)));
        owner("INSERT INTO interim_summaries(session_id, ordinal, content) VALUES ('" + sid + "', 1, 'summary " + secret + "')");
        return sid;
    }

    private void owner(String sql) throws Exception {
        try (Connection c = DB.owner(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private long count(String sql) throws Exception {
        try (Connection c = DB.owner(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM (" + sql + ") x")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ deleting a session

    @Test
    void aSessionIsDeletedWithItsEventsAndSummariesAndOnlyByItsOwner() throws Exception {
        UUID a = newUser("a");
        UUID b = newUser("b");
        UUID sa = sessionWithData(a, "a-secret");
        UUID sb = sessionWithData(b, "b-secret");

        assertThatThrownBy(() -> DbContext.runAs(b, false, () -> sessions.delete(sa))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("SELECT 1 FROM interview_sessions WHERE id = '" + sa + "'")).isEqualTo(1);

        DbContext.runAs(a, false, () -> sessions.delete(sa));

        assertThat(count("SELECT 1 FROM interview_sessions WHERE id = '" + sa + "'")).isZero();
        assertThat(count("SELECT 1 FROM session_events WHERE session_id = '" + sa + "'")).isZero();
        assertThat(count("SELECT 1 FROM interim_summaries WHERE session_id = '" + sa + "'")).isZero();
        assertThat(count("SELECT 1 FROM session_events WHERE session_id = '" + sb + "'")).as("B's data untouched").isPositive();
    }

    // ------------------------------------------------------------------ deleting an account

    @Test
    void aWrongPasswordDeletesNothing() throws Exception {
        UUID a = newUser("a");
        sessionWithData(a, "keep-me");
        assertThatThrownBy(() -> DbContext.callAsInScope(a, DbContext.SCOPE_AUTH, () -> accounts.deleteAccount(a, "Wrong-pass1")))
                .isInstanceOf(WrongPasswordException.class);
        assertThat(count("SELECT 1 FROM users WHERE id = '" + a + "'")).isEqualTo(1);
        assertThat(count("SELECT 1 FROM interview_sessions WHERE user_id = '" + a + "'")).isPositive();
    }

    @Test
    void deletingAnAccountRemovesEverythingOfTheirsAndNothingOfAnyoneElses() throws Exception {
        UUID a = newUser("a");
        UUID b = newUser("b");
        UUID owner = UUID.randomUUID();
        UUID sa1 = sessionWithData(a, "a-one");
        UUID sa2 = sessionWithData(a, "a-two");
        UUID sb = sessionWithData(b, "b-one");
        UUID published = DbContext.callAs(a, false, () -> bank.submitForReview("A published idea", "Prompt " + UUID.randomUUID(), a)).getId();
        UUID unpublished = DbContext.callAs(a, false, () -> bank.submitForReview("A private idea", "Prompt " + UUID.randomUUID(), a)).getId();
        UUID bPending = DbContext.callAs(b, false, () -> bank.submitForReview("B idea", "Prompt " + UUID.randomUUID(), b)).getId();
        DbContext.callAs(owner, true, () -> bank.approve(published));

        List<UUID> deleted = DbContext.callAsInScope(a, DbContext.SCOPE_AUTH, () -> accounts.deleteAccount(a, PASSWORD));

        assertThat(deleted).containsExactlyInAnyOrder(sa1, sa2);
        assertThat(count("SELECT 1 FROM users WHERE id = '" + a + "'")).isZero();
        assertThat(count("SELECT 1 FROM interview_sessions WHERE user_id = '" + a + "'")).isZero();
        assertThat(count("SELECT 1 FROM session_events WHERE session_id IN ('" + sa1 + "','" + sa2 + "')")).isZero();
        assertThat(count("SELECT 1 FROM interim_summaries WHERE session_id IN ('" + sa1 + "','" + sa2 + "')")).isZero();
        assertThat(count("SELECT 1 FROM refresh_tokens WHERE user_id = '" + a + "'")).as("every device signed out").isZero();
        assertThat(count("SELECT 1 FROM questions WHERE id = '" + unpublished + "'")).as("their unpublished prompt is deleted").isZero();
        assertThat(count("SELECT 1 FROM questions WHERE id = '" + published + "' AND submitted_by IS NULL"))
                .as("a published prompt stays in the bank, unlinked from them").isEqualTo(1);

        // everyone else is untouched
        assertThat(count("SELECT 1 FROM users WHERE id = '" + b + "'")).isEqualTo(1);
        assertThat(count("SELECT 1 FROM session_events WHERE session_id = '" + sb + "'")).isPositive();
        assertThat(count("SELECT 1 FROM refresh_tokens WHERE user_id = '" + b + "'")).isPositive();
        assertThat(count("SELECT 1 FROM questions WHERE id = '" + bPending + "' AND submitted_by = '" + b + "'")).isEqualTo(1);
    }

    @Test
    void accountDeletionOutsideTheAuthScopeIsRefusedAndRollsBackCompletely() throws Exception {
        UUID a = newUser("a");
        sessionWithData(a, "must-survive");
        // As an ordinary signed-in user only (no auth scope) the database will not let the account row go.
        assertThatThrownBy(() -> DbContext.callAs(a, false, () -> accounts.deleteAccount(a, PASSWORD)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT 1 FROM users WHERE id = '" + a + "'")).isEqualTo(1);
        assertThat(count("SELECT 1 FROM interview_sessions WHERE user_id = '" + a + "'")).as("rolled back, not half-deleted").isPositive();
    }

    // ------------------------------------------------------------------ export

    @Test
    void theExportHoldsTheCallersDataAndNobodyElses() throws Exception {
        UUID a = newUser("a");
        UUID b = newUser("b");
        sessionWithData(a, "alpha-words");
        sessionWithData(b, "bravo-words");

        Map<String, Object> export = DbContext.callAs(a, false, () -> accounts.export(a));
        String text = export.toString();
        assertThat(text).contains("alpha-words").contains(a.toString()).doesNotContain("bravo-words").doesNotContain(b.toString());
        assertThat((List<?>) export.get("sessions")).hasSize(1);
        assertThat(export).containsKeys("profile", "submittedQuestions", "sessions", "exportedAt");
    }

    // ------------------------------------------------------------------ retention

    private void ageSession(UUID sid, int days) throws Exception {
        owner("UPDATE interview_sessions SET started_at = now() - interval '" + days + " days' WHERE id = '" + sid + "'");
    }

    @Test
    void theRetentionPurgeDeletesOnlyInterviewsOlderThanTheConfiguredPeriod() throws Exception {
        UUID a = newUser("a");
        UUID veryOld = sessionWithData(a, "old");
        UUID recent = sessionWithData(a, "recent");
        ageSession(veryOld, 400);
        ageSession(recent, 10);

        retention.purge(Instant.now().minus(Duration.ofDays(365)));

        assertThat(count("SELECT 1 FROM interview_sessions WHERE id = '" + veryOld + "'")).isZero();
        assertThat(count("SELECT 1 FROM session_events WHERE session_id = '" + veryOld + "'")).as("with its events").isZero();
        assertThat(count("SELECT 1 FROM interview_sessions WHERE id = '" + recent + "'")).isEqualTo(1);
    }

    @Test
    void evenAMisconfiguredPurgeCannotTouchInterviewsYoungerThanThirtyDays() throws Exception {
        UUID a = newUser("a");
        UUID forty = sessionWithData(a, "forty");
        UUID ten = sessionWithData(a, "ten");
        UUID fresh = sessionWithData(a, "fresh");
        ageSession(forty, 40);
        ageSession(ten, 10);

        // "Delete everything from before tomorrow" - the database's own floor still protects the recent ones.
        retention.purge(Instant.now().plus(Duration.ofDays(1)));

        assertThat(count("SELECT 1 FROM interview_sessions WHERE id = '" + forty + "'")).isZero();
        assertThat(count("SELECT 1 FROM interview_sessions WHERE id = '" + ten + "'")).isEqualTo(1);
        assertThat(count("SELECT 1 FROM interview_sessions WHERE id = '" + fresh + "'")).isEqualTo(1);

        // and the retention scope cannot be used to delete one directly either
        DbContext.callInScope(DbContext.SCOPE_RETENTION, () -> {
            sessionRepo.deleteById(ten);
            return null;
        });
        assertThat(count("SELECT 1 FROM interview_sessions WHERE id = '" + ten + "'")).isEqualTo(1);
    }

    @Test
    void theRetentionScopeIsNotAWayToReadOrDeleteAnythingElse() throws Exception {
        UUID a = newUser("a");
        UUID sid = sessionWithData(a, "ordinary");
        ageSession(sid, 90);
        // sessions only; never users or events
        assertThat(DbContext.callInScope(DbContext.SCOPE_RETENTION, () -> sessionRepo.findByStartedAtBefore(Instant.now()))).extracting("id").contains(sid);
        assertThat(DbContext.callInScope(DbContext.SCOPE_RETENTION, () -> eventLog.since(sid, 0))).isEmpty();
        assertThat(count("SELECT 1 FROM users WHERE id = '" + a + "'")).isEqualTo(1);
    }

    @Test
    void theRetentionPeriodHasASaneMinimum() {
        assertThatThrownBy(() -> new RetentionProperties(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionProperties(29)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RetentionProperties(0).sessionDays()).isZero();
        assertThat(new RetentionProperties(30).sessionDays()).isEqualTo(30);
        assertThat(new RetentionProperties(365).sessionDays()).isEqualTo(365);
    }
}
