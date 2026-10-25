package com.ossature.interviewer.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * H17 / R6: the row-level-security policies of V7, tested against a real Postgres as the restricted
 * application role. Every test below is "can user A reach user B's data" or "does a missing context
 * fail closed" - the failure modes RLS exists for.
 */
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
class RlsPolicyTest {

    static ScratchDatabase db;

    // two ordinary users, one owner, and their data
    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static final UUID OWNER = UUID.randomUUID();
    static UUID approvedQ, pendingByA, pendingByB, sessionA, sessionB, sessionBEnded;

    @BeforeAll
    static void setUp() throws Exception {
        db = ScratchDatabase.create();
        try (Connection c = db.owner(); Statement s = c.createStatement()) {
            for (UUID u : List.of(A, B, OWNER)) {
                s.execute("INSERT INTO users(id,email,display_name,password_hash) VALUES ('" + u + "','" + u + "@x.com','n','h')");
            }
            approvedQ = insertQuestion(c, "approved-q", "SEED", "APPROVED", null);
            pendingByA = insertQuestion(c, "pending-a", "COMMUNITY", "PENDING", A);
            pendingByB = insertQuestion(c, "pending-b", "COMMUNITY", "PENDING", B);
            sessionA = insertSession(c, A, approvedQ, "ACTIVE");
            sessionB = insertSession(c, B, approvedQ, "ACTIVE");
            sessionBEnded = insertSession(c, B, approvedQ, "ENDED");
            for (UUID sid : List.of(sessionA, sessionB)) {
                s.execute("INSERT INTO session_events(session_id,seq,type,payload) VALUES ('" + sid + "',1,'chat.message','{\"text\":\"secret of " + sid + "\"}')");
                s.execute("INSERT INTO interim_summaries(session_id,ordinal,content) VALUES ('" + sid + "',1,'summary of " + sid + "')");
            }
        }
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private static UUID insertQuestion(Connection c, String slug, String source, String status, UUID by) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO questions(id,slug,title,prompt,source,status,submitted_by) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setString(2, slug);
            ps.setString(3, "t " + slug);
            ps.setString(4, "p " + slug);
            ps.setString(5, source);
            ps.setString(6, status);
            ps.setObject(7, by);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertSession(Connection c, UUID user, UUID question, String state) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO interview_sessions(id,user_id,question_id,state) VALUES (?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, user);
            ps.setObject(3, question);
            ps.setString(4, state);
            ps.executeUpdate();
        }
        return id;
    }

    // ------------------------------------------------------------------ helpers

    /** Acts like one application transaction: sets the context (or none) LOCAL to the transaction. */
    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static <T> T asUser(UUID user, boolean owner, Work<T> work) throws SQLException {
        return inTx(user == null ? "" : user.toString(), owner ? "true" : "false", "", work);
    }

    private static <T> T inScope(String scope, Work<T> work) throws SQLException {
        return inTx("", "false", scope, work);
    }

    private static <T> T anonymously(Work<T> work) throws SQLException {
        try (Connection c = db.app()) {
            c.setAutoCommit(false);
            try {
                return work.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    private static <T> T inTx(String user, String owner, String scope, Work<T> work) throws SQLException {
        try (Connection c = db.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT set_config('app.user_id', ?, true), set_config('app.is_owner', ?, true), set_config('app.scope', ?, true)")) {
                ps.setString(1, user);
                ps.setString(2, owner);
                ps.setString(3, scope);
                ps.execute();
            }
            try {
                return work.run(c);
            } finally {
                c.rollback(); // tests never keep their changes
            }
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM (" + sql + ") t")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> column(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static int update(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        }
    }

    // ------------------------------------------------------------------ the roles themselves

    @Test
    void theApplicationRoleIsGenuinelyRestricted() throws Exception {
        try (Connection c = db.owner()) {
            List<String> rows = column(c, "SELECT rolsuper || ',' || rolbypassrls || ',' || rolcreaterole || ',' || rolcreatedb FROM pg_roles WHERE rolname = 'ossature_app'");
            assertThat(rows).containsExactly("false,false,false,false");
            assertThat(column(c, "SELECT tableowner FROM pg_tables WHERE tablename = 'users'")).containsExactly(db.ownerUser());
            // every protected table has RLS enabled AND forced
            assertThat(column(c, "SELECT relname FROM pg_class WHERE relname IN ('users','questions','interview_sessions','session_events','interim_summaries') AND relrowsecurity AND relforcerowsecurity ORDER BY relname"))
                    .containsExactly("interim_summaries", "interview_sessions", "questions", "session_events", "users");
        }
    }

    @Test
    void theApplicationRoleCannotEscalateOrChangeTheSchema() throws Exception {
        try (Connection c = db.app(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("SET ROLE " + db.ownerUser())).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("CREATE TABLE evil(x int)")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("ALTER TABLE users DISABLE ROW LEVEL SECURITY")).hasMessageContaining("must be owner");
            assertThatThrownBy(() -> s.execute("DROP TABLE users")).hasMessageContaining("must be owner");
        }
    }

    @Test
    void theApplicationRoleHoldsOnlyTheGrantsTheCodeNeeds() throws Exception {
        try (Connection c = db.app(); Statement s = c.createStatement()) {
            // Deleting sessions, questions and accounts is granted since V11 (H21) - but only through the policies:
            // with no acting user (or no auth scope) such a statement matches nothing.
            assertThat(s.executeUpdate("DELETE FROM interview_sessions")).isZero();
            assertThat(s.executeUpdate("DELETE FROM users")).isZero();
            assertThat(s.executeUpdate("DELETE FROM questions")).isZero();
            // Events and summaries are never deleted directly, only by the cascade from their session.
            assertThatThrownBy(() -> s.execute("DELETE FROM session_events")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("DELETE FROM interim_summaries")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("UPDATE users SET email = 'x'")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("TRUNCATE session_events")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeQuery("SELECT * FROM agent_sessions")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeQuery("SELECT * FROM flyway_schema_history")).hasMessageContaining("permission denied");
        }
    }

    @Test
    void theAgentRoleCanTouchOnlyItsOwnTable() throws Exception {
        try (Connection c = db.agent(); Statement s = c.createStatement()) {
            assertThat(count(c, "SELECT * FROM agent_sessions")).isZero(); // allowed
            for (String table : List.of("users", "questions", "interview_sessions", "session_events", "interim_summaries")) {
                assertThatThrownBy(() -> s.executeQuery("SELECT * FROM " + table)).hasMessageContaining("permission denied");
            }
            assertThatThrownBy(() -> s.execute("CREATE TABLE evil(x int)")).hasMessageContaining("permission denied");
        }
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void withNoContextAtAllNothingPrivateIsVisible() throws Exception {
        anonymously(c -> {
            assertThat(count(c, "SELECT * FROM users")).isZero();
            assertThat(count(c, "SELECT * FROM interview_sessions")).isZero();
            assertThat(count(c, "SELECT * FROM session_events")).isZero();
            assertThat(count(c, "SELECT * FROM interim_summaries")).isZero();
            return null;
        });
    }

    @Test
    void anAnonymousCallerSeesOnlyTheApprovedQuestionBank() throws Exception {
        // (the migrations also seed some built-in approved questions, so compare with what is approved)
        List<String> approved;
        try (Connection owner = db.owner()) {
            approved = column(owner, "SELECT slug FROM questions WHERE status = 'APPROVED'");
        }
        anonymously(c -> {
            List<String> visible = column(c, "SELECT slug FROM questions");
            assertThat(visible).containsExactlyInAnyOrderElementsOf(approved);
            assertThat(visible).contains("approved-q").doesNotContain("pending-a", "pending-b");
            return null;
        });
    }

    @Test
    void aMalformedUserIdFailsTheQueryInsteadOfMatchingRows() throws Exception {
        assertThatThrownBy(() -> inTx("not-a-uuid", "false", "", c -> count(c, "SELECT * FROM interview_sessions")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void contextDoesNotLeakIntoTheNextTransactionOnAReusedConnection() throws Exception {
        try (Connection c = db.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
                ps.setString(1, A.toString());
                ps.execute();
            }
            assertThat(count(c, "SELECT * FROM interview_sessions")).isEqualTo(1); // acting as A
            c.commit();

            // same physical connection, new transaction, no context set: A's identity must be gone
            assertThat(count(c, "SELECT * FROM interview_sessions")).isZero();
            assertThat(column(c, "SELECT coalesce(current_setting('app.user_id', true), '')")).containsExactly("");
            c.commit();
        }
    }

    // ------------------------------------------------------------------ sessions, events, summaries

    @Test
    void aUserSeesOnlyTheirOwnSessionsEventsAndSummaries() throws Exception {
        asUser(A, false, c -> {
            assertThat(column(c, "SELECT id::text FROM interview_sessions")).containsExactly(sessionA.toString());
            assertThat(column(c, "SELECT payload->>'text' FROM session_events")).containsExactly("secret of " + sessionA);
            assertThat(column(c, "SELECT content FROM interim_summaries")).containsExactly("summary of " + sessionA);
            return null;
        });
        asUser(B, false, c -> {
            assertThat(column(c, "SELECT id::text FROM interview_sessions ORDER BY id")).containsExactlyInAnyOrder(sessionB.toString(), sessionBEnded.toString());
            return null;
        });
    }

    @Test
    void aUserCannotReadAnotherUsersSessionByIdEvenWhenAskingDirectly() throws Exception {
        asUser(A, false, c -> {
            assertThat(count(c, "SELECT * FROM interview_sessions WHERE id = '" + sessionB + "'")).isZero();
            assertThat(count(c, "SELECT * FROM session_events WHERE session_id = '" + sessionB + "'")).isZero();
            assertThat(count(c, "SELECT * FROM interim_summaries WHERE session_id = '" + sessionB + "'")).isZero();
            return null;
        });
    }

    @Test
    void aUserCannotChangeAnotherUsersSession() throws Exception {
        asUser(A, false, c -> {
            assertThat(update(c, "UPDATE interview_sessions SET state = 'ENDED' WHERE id = '" + sessionB + "'")).isZero();
            assertThat(update(c, "UPDATE interview_sessions SET user_id = '" + A + "' WHERE id = '" + sessionB + "'")).isZero(); // cannot steal it
            return null;
        });
    }

    @Test
    void aUserCannotCreateASessionInSomeoneElsesName() throws Exception {
        assertThatThrownBy(() -> asUser(A, false, c -> update(c,
                "INSERT INTO interview_sessions(user_id, question_id) VALUES ('" + B + "','" + approvedQ + "')")))
                .hasMessageContaining("row-level security");
        asUser(A, false, c -> {
            assertThat(update(c, "INSERT INTO interview_sessions(user_id, question_id) VALUES ('" + A + "','" + approvedQ + "')")).isEqualTo(1);
            return null;
        });
    }

    @Test
    void aUserCanAppendEventsAndSummariesOnlyToTheirOwnSession() throws Exception {
        asUser(A, false, c -> {
            assertThat(update(c, "INSERT INTO session_events(session_id,seq,type,payload) VALUES ('" + sessionA + "',2,'chat.message','{}')")).isEqualTo(1);
            assertThat(update(c, "INSERT INTO interim_summaries(session_id,ordinal,content) VALUES ('" + sessionA + "',2,'x')")).isEqualTo(1);
            return null;
        });
        assertThatThrownBy(() -> asUser(A, false, c -> update(c,
                "INSERT INTO session_events(session_id,seq,type,payload) VALUES ('" + sessionB + "',9,'chat.message','{\"forged\":true}')")))
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> asUser(A, false, c -> update(c,
                "INSERT INTO interim_summaries(session_id,ordinal,content) VALUES ('" + sessionB + "',9,'forged')")))
                .hasMessageContaining("row-level security");
    }

    @Test
    void theOwnerRoleDoesNotGrantAccessToOtherPeoplesSessions() throws Exception {
        asUser(OWNER, true, c -> {
            assertThat(count(c, "SELECT * FROM interview_sessions")).isZero();
            assertThat(count(c, "SELECT * FROM session_events")).isZero();
            return null;
        });
    }

    // ------------------------------------------------------------------ questions

    @Test
    void aSubmitterSeesTheirOwnPendingQuestionsButNotOthers() throws Exception {
        asUser(A, false, c -> {
            assertThat(column(c, "SELECT slug FROM questions")).contains("approved-q", "pending-a").doesNotContain("pending-b");
            return null;
        });
        asUser(B, false, c -> {
            assertThat(column(c, "SELECT slug FROM questions")).contains("approved-q", "pending-b").doesNotContain("pending-a");
            return null;
        });
    }

    @Test
    void theOwnerSeesEveryQuestion() throws Exception {
        asUser(OWNER, true, c -> {
            assertThat(column(c, "SELECT slug FROM questions")).contains("approved-q", "pending-a", "pending-b");
            return null;
        });
    }

    @Test
    void communitySubmissionsMustBePendingAndInTheSubmittersOwnName() throws Exception {
        asUser(A, false, c -> {
            assertThat(update(c, "INSERT INTO questions(slug,title,prompt,source,status,submitted_by) VALUES ('ok','t','p','COMMUNITY','PENDING','" + A + "')")).isEqualTo(1);
            return null;
        });
        // pre-approved by the submitter
        assertThatThrownBy(() -> asUser(A, false, c -> update(c,
                "INSERT INTO questions(slug,title,prompt,source,status,submitted_by) VALUES ('x1','t','p','COMMUNITY','APPROVED','" + A + "')")))
                .hasMessageContaining("row-level security");
        // as someone else
        assertThatThrownBy(() -> asUser(A, false, c -> update(c,
                "INSERT INTO questions(slug,title,prompt,source,status,submitted_by) VALUES ('x2','t','p','COMMUNITY','PENDING','" + B + "')")))
                .hasMessageContaining("row-level security");
        // pretending to be a built-in question
        assertThatThrownBy(() -> asUser(A, false, c -> update(c,
                "INSERT INTO questions(slug,title,prompt,source,status,submitted_by) VALUES ('x3','t','p','SEED','PENDING','" + A + "')")))
                .hasMessageContaining("row-level security");
        // anonymous
        assertThatThrownBy(() -> anonymously(c -> update(c,
                "INSERT INTO questions(slug,title,prompt,source,status,submitted_by) VALUES ('x4','t','p','COMMUNITY','PENDING',NULL)")))
                .hasMessageContaining("row-level security");
    }

    @Test
    void onlyTheOwnerMayApproveOrRejectAQuestion() throws Exception {
        asUser(A, false, c -> {
            assertThat(update(c, "UPDATE questions SET status = 'APPROVED' WHERE id = '" + pendingByA + "'")).isZero(); // not even their own
            assertThat(update(c, "UPDATE questions SET status = 'APPROVED' WHERE id = '" + pendingByB + "'")).isZero();
            return null;
        });
        asUser(OWNER, true, c -> {
            assertThat(update(c, "UPDATE questions SET status = 'APPROVED' WHERE id = '" + pendingByB + "'")).isEqualTo(1);
            return null;
        });
    }

    @Test
    void anApprovedQuestionCannotBeEditedByAnOrdinaryUser() throws Exception {
        asUser(A, false, c -> {
            assertThat(update(c, "UPDATE questions SET prompt = 'HACKED' WHERE id = '" + approvedQ + "'")).isZero();
            return null;
        });
    }

    // ------------------------------------------------------------------ users and scopes

    @Test
    void aSignedInUserSeesOnlyTheirOwnAccountRow() throws Exception {
        asUser(A, false, c -> {
            assertThat(column(c, "SELECT id::text FROM users")).containsExactly(A.toString());
            assertThat(count(c, "SELECT password_hash FROM users WHERE id = '" + B + "'")).isZero();
            return null;
        });
    }

    @Test
    void onlyTheAuthScopeMayLookUpOrCreateAccounts() throws Exception {
        inScope(DbContext.SCOPE_AUTH, c -> {
            assertThat(count(c, "SELECT * FROM users WHERE email = '" + B + "@x.com'")).isEqualTo(1);
            assertThat(update(c, "INSERT INTO users(email,display_name,password_hash) VALUES ('new@x.com','n','h')")).isEqualTo(1);
            return null;
        });
        assertThatThrownBy(() -> asUser(A, false, c -> update(c, "INSERT INTO users(email,display_name,password_hash) VALUES ('sneaky@x.com','n','h')")))
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> anonymously(c -> update(c, "INSERT INTO users(email,display_name,password_hash) VALUES ('anon@x.com','n','h')")))
                .hasMessageContaining("row-level security");
    }

    @Test
    void theSchedulerScopeCanListActiveSessionsButNothingElse() throws Exception {
        inScope(DbContext.SCOPE_SCHEDULER, c -> {
            assertThat(column(c, "SELECT id::text FROM interview_sessions")).containsExactlyInAnyOrder(sessionA.toString(), sessionB.toString()); // ACTIVE only, all users
            assertThat(count(c, "SELECT * FROM session_events")).isZero();    // not the content...
            assertThat(count(c, "SELECT * FROM interim_summaries")).isZero(); // ...nor the summaries
            assertThat(count(c, "SELECT * FROM users")).isZero();
            assertThat(update(c, "UPDATE interview_sessions SET state = 'ENDED'")).isZero(); // read-only
            return null;
        });
    }

    @Test
    void anUnknownScopeGrantsNothing() throws Exception {
        inScope("admin", c -> {
            assertThat(count(c, "SELECT * FROM users")).isZero();
            assertThat(count(c, "SELECT * FROM interview_sessions")).isZero();
            return null;
        });
    }
}
