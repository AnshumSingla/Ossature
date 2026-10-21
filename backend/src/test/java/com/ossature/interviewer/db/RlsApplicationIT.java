package com.ossature.interviewer.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.domain.SessionState;
import com.ossature.interviewer.repo.InterviewSessionRepository;
import com.ossature.interviewer.service.AuthService;
import com.ossature.interviewer.service.EventLogService;
import com.ossature.interviewer.service.InvalidCredentialsException;
import com.ossature.interviewer.service.QuestionBankService;
import com.ossature.interviewer.service.SessionService;
import com.ossature.interviewer.service.SummaryScheduler;
import com.ossature.interviewer.web.dto.AuthDtos.LoginRequest;
import com.ossature.interviewer.web.dto.AuthDtos.SignupRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * H17: the whole application running as the restricted database role against a real Postgres - the
 * real services, the real transaction manager, the real policies. Checks both that the identity is
 * applied to each transaction and that cross-user access fails through the application's own code
 * paths, not just through hand-written SQL (see RlsPolicyTest for that).
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1", // one connection, so every transaction reuses it
})
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
class RlsApplicationIT {

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

    @Autowired AuthService auth;
    @Autowired SessionService sessions;
    @Autowired EventLogService eventLog;
    @Autowired QuestionBankService bank;
    @Autowired InterviewSessionRepository sessionRepo;
    @Autowired SummaryScheduler scheduler;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;

    private UUID signup(String name) {
        String email = name + "-" + UUID.randomUUID() + "@example.com";
        return DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.signup(new SignupRequest(email, "Passw0rd1", name))).userId();
    }

    // (When an agent service is running, creating a session also stores the interviewer's opening turn, so
    // the tests below look for their own event rather than assuming the log holds only that one.)

    private <T> T tx(java.util.function.Supplier<T> body) {
        return new TransactionTemplate(tm).execute(status -> body.get());
    }

    private String setting(String name) {
        return tx(() -> jdbc.queryForObject("SELECT coalesce(current_setting('" + name + "', true), '')", String.class));
    }

    // ------------------------------------------------------------------ the identity reaches the database

    @Test
    void theActingIdentityIsAppliedToEachTransactionAndNeverCarriedOver() {
        UUID user = UUID.randomUUID();

        assertThat(DbContext.callAs(user, true, () -> setting("app.user_id"))).isEqualTo(user.toString());
        assertThat(DbContext.callAs(user, true, () -> setting("app.is_owner"))).isEqualTo("true");
        assertThat(DbContext.callInScope(DbContext.SCOPE_SCHEDULER, () -> setting("app.scope"))).isEqualTo("scheduler");

        // The pool has exactly one connection, so these transactions reuse the very same one.
        assertThat(setting("app.user_id")).isEmpty();
        assertThat(setting("app.scope")).isEmpty();
        assertThat(DbContext.callAs(UUID.randomUUID(), false, () -> setting("app.user_id"))).isNotEqualTo(user.toString());
        assertThat(setting("app.user_id")).isEmpty();
    }

    @Test
    void theApplicationReallyRunsAsTheRestrictedRole() {
        assertThat(tx(() -> jdbc.queryForObject("SELECT current_user", String.class))).isEqualTo(ScratchDatabase.APP_USER);
        assertThat(tx(() -> jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))).isFalse();
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void accountsCanOnlyBeCreatedAndLookedUpInTheAuthScope() {
        String email = "scope-" + UUID.randomUUID() + "@example.com";
        assertThatThrownBy(() -> auth.signup(new SignupRequest(email, "Passw0rd1", "x"))).isInstanceOf(Exception.class);

        DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.signup(new SignupRequest(email, "Passw0rd1", "x")));
        assertThat(DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.login(new LoginRequest(email, "Passw0rd1"))).email()).isEqualTo(email);
        assertThatThrownBy(() -> DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.login(new LoginRequest(email, "wrong-Pass1"))))
                .isInstanceOf(InvalidCredentialsException.class);
        // outside the auth scope a login cannot even find the account
        assertThatThrownBy(() -> auth.login(new LoginRequest(email, "Passw0rd1"))).isInstanceOf(InvalidCredentialsException.class);
    }

    // ------------------------------------------------------------------ sessions and their events

    @Test
    void anotherUserCannotReachASessionOrItsEventsThroughTheApplicationsOwnServices() {
        UUID a = signup("a");
        UUID b = signup("b");
        UUID question = bank.listApproved().get(0).getId();

        InterviewSession session = DbContext.callAs(a, false, () -> sessions.create(a, question));
        UUID sid = session.getId();
        DbContext.runAs(a, false, () -> eventLog.append(sid, "chat.message", Map.of("sender", "candidate", "text", "private words")));

        // the owner can read it all back
        assertThat(DbContext.callAs(a, false, () -> sessions.get(sid)).getUserId()).isEqualTo(a);
        assertThat(DbContext.callAs(a, false, () -> eventLog.since(sid, 0))).filteredOn(e -> e.getPayload().contains("private words")).hasSize(1);
        assertThat(DbContext.callAs(a, false, () -> sessions.listForUser(a))).extracting(InterviewSession::getId).contains(sid);

        // another user gets "unknown session" and an empty log - not a "forbidden" that confirms it exists
        assertThatThrownBy(() -> DbContext.callAs(b, false, () -> sessions.get(sid)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown session");
        assertThat(DbContext.callAs(b, false, () -> eventLog.since(sid, 0))).isEmpty();
        assertThat(DbContext.callAs(b, false, () -> sessions.listForUser(a))).isEmpty(); // even asking for A's list by id
        // ...cannot write into it...
        assertThatThrownBy(() -> DbContext.runAs(b, false, () -> eventLog.append(sid, "chat.message", Map.of("text", "forged"))))
                .isInstanceOf(Exception.class);
        // ...and the project owner role grants no access to other people's sessions either.
        assertThatThrownBy(() -> DbContext.callAs(UUID.randomUUID(), true, () -> sessions.get(sid)))
                .isInstanceOf(IllegalArgumentException.class);
        // an anonymous caller sees nothing at all
        assertThatThrownBy(() -> sessions.get(sid)).isInstanceOf(IllegalArgumentException.class);

        // and A's data is still intact after all of that
        assertThat(DbContext.callAs(a, false, () -> eventLog.since(sid, 0))).filteredOn(e -> e.getPayload().contains("private words")).hasSize(1);
    }

    @Test
    void aSessionCannotBeStartedOnAnotherUsersPendingQuestion() {
        UUID a = signup("a");
        UUID b = signup("b");
        UUID pending = DbContext.callAs(a, false, () -> bank.submitForReview("A's idea", "Design something.", a)).getId();

        assertThatThrownBy(() -> DbContext.callAs(b, false, () -> sessions.create(b, pending)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ the question bank

    @Test
    void theReviewWorkflowRespectsVisibilityAtTheDatabase() {
        UUID a = signup("a");
        UUID b = signup("b");
        UUID owner = UUID.randomUUID();

        UUID id = DbContext.callAs(a, false, () -> bank.submitForReview("Same title", "Prompt " + UUID.randomUUID(), a)).getId();
        // a second submission with the same title must not collide on the slug just because it can't see the first
        UUID id2 = DbContext.callAs(b, false, () -> bank.submitForReview("Same title", "Another prompt", b)).getId();
        assertThat(id2).isNotEqualTo(id);

        // submitter and owner can see it; another user and anonymous callers cannot
        assertThat(DbContext.callAs(a, false, () -> bank.get(id)).getId()).isEqualTo(id);
        assertThat(DbContext.callAs(owner, true, () -> bank.get(id)).getId()).isEqualTo(id);
        assertThatThrownBy(() -> DbContext.callAs(b, false, () -> bank.get(id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bank.get(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(bank.listApproved()).extracting(q -> q.getId()).doesNotContain(id, id2);

        // only the owner sees the pending list, and only the owner can approve
        assertThat(DbContext.callAs(owner, true, bank::listPending)).extracting(q -> q.getId()).contains(id, id2);
        assertThat(DbContext.callAs(b, false, bank::listPending)).extracting(q -> q.getId()).doesNotContain(id);
        assertThatThrownBy(() -> DbContext.callAs(a, false, () -> bank.approve(id))).isInstanceOf(Exception.class);

        DbContext.callAs(owner, true, () -> bank.approve(id));
        assertThat(bank.listApproved()).extracting(q -> q.getId()).contains(id).doesNotContain(id2);
    }

    // ------------------------------------------------------------------ background work

    @Test
    void theSchedulerCanFindActiveSessionsButWorksAsEachOwner() {
        UUID a = signup("a");
        UUID question = bank.listApproved().get(0).getId();
        UUID sid = DbContext.callAs(a, false, () -> sessions.create(a, question)).getId();

        List<InterviewSession> seen = DbContext.callInScope(DbContext.SCOPE_SCHEDULER, () -> sessionRepo.findByState(SessionState.ACTIVE));
        assertThat(seen).extracting(InterviewSession::getId).contains(sid);
        // in that scope the session's content is still off limits
        assertThat(DbContext.callInScope(DbContext.SCOPE_SCHEDULER, () -> eventLog.since(sid, 0))).isEmpty();

        // a full tick (list, then act as each owner) runs without error against real RLS
        scheduler.tick();
    }
}
