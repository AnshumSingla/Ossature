package com.ossature.interviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossature.interviewer.db.ScratchDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * C2 / C3 of the security test plan, end to end. Two ordinary users and the owner try to reach each other's
 * data over REST and the WebSocket, send fields they must not be able to set, and share a deliberately tiny
 * connection pool (see {@link FullStackSupport}).
 */
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
class TenantIsolationFullStackTest extends FullStackSupport {

    // ------------------------------------------------------------------ C3: REST isolation

    @Test
    void theOwnerOfASessionCanReadItAndAnotherUserGetsNothing() throws Exception {
        String base = "/api/sessions/" + sessionA;
        for (String suffix : List.of("", "/events", "/summaries", "/debrief", "/diagram")) {
            assertThat(call("GET", base + suffix, a.token(), null).status()).as("A GET " + suffix).isEqualTo(200);
            Resp other = call("GET", base + suffix, b.token(), null);
            assertThat(other.status()).as("B GET " + suffix).isEqualTo(404);
            assertThat(other.body()).doesNotContain(A_SECRET);
        }
        assertThat(call("POST", base + "/ws-ticket", b.token(), null).status()).isEqualTo(404);
        assertThat(call("DELETE", base, b.token(), null).status()).isEqualTo(404);
        assertThat(call("GET", base, a.token(), null).status()).as("A's session survived B's delete attempt").isEqualTo(200);
        // anonymous: not even a hint
        assertThat(call("GET", base + "/events", null, null).status()).isEqualTo(403);
    }

    /** The id being asked about and the response timestamp are the only things allowed to differ. */
    private static String maskVolatile(String body, UUID id) {
        return body.replace(id.toString(), "ID").replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"T\"");
    }

    @Test
    void someoneElsesSessionIsIndistinguishableFromOneThatDoesNotExist() throws Exception {
        UUID nonexistent = UUID.randomUUID();
        for (String suffix : List.of("", "/events", "/summaries", "/debrief", "/diagram")) {
            Resp real = call("GET", "/api/sessions/" + sessionA + suffix, b.token(), null);
            Resp none = call("GET", "/api/sessions/" + nonexistent + suffix, b.token(), null);
            assertThat(real.status()).isEqualTo(none.status());
            assertThat(maskVolatile(real.body(), sessionA)).isEqualTo(maskVolatile(none.body(), nonexistent));
        }
    }

    @Test
    void sessionListsAreOwnOnlyAndTheIdInTheTokenIsTheOnlyWayToAskForOne() throws Exception {
        UUID sessionB = createSession(b);
        List<String> idsSeenByA = new ArrayList<>();
        call("GET", "/api/sessions", a.token(), null).json().forEach(n -> idsSeenByA.add(n.get("id").asString()));
        assertThat(idsSeenByA).contains(sessionA.toString()).doesNotContain(sessionB.toString());
        // a query parameter naming another user changes nothing
        List<String> viaParam = new ArrayList<>();
        call("GET", "/api/sessions?userId=" + a.id(), b.token(), null).json().forEach(n -> viaParam.add(n.get("id").asString()));
        assertThat(viaParam).contains(sessionB.toString()).doesNotContain(sessionA.toString());
    }

    @Test
    void anotherUsersPendingQuestionIsInvisibleAndOnlyTheOwnerCanModerate() throws Exception {
        UUID pending = UUID.fromString(call("POST", "/api/questions", a.token(),
                "{\"title\":\"A's idea\",\"prompt\":\"Design something.\"}").json().get("id").asString());
        assertThat(call("GET", "/api/questions/" + pending, a.token(), null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/questions/" + pending, b.token(), null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/questions/" + pending, null, null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/questions", b.token(), null).body()).doesNotContain(pending.toString());

        assertThat(call("POST", "/api/questions/" + pending + "/approve", b.token(), null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/questions/" + pending + "/approve", a.token(), null).status()).as("even the submitter").isEqualTo(403);
        assertThat(call("GET", "/api/questions/pending", b.token(), null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/sessions", b.token(), "{\"questionId\":\"" + pending + "\"}").status()).as("run an unreviewed prompt").isEqualTo(404);

        assertThat(call("GET", "/api/questions/pending", owner.token(), null).body()).contains(pending.toString());
        assertThat(call("POST", "/api/questions/" + pending + "/approve", owner.token(), null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/questions/" + pending, b.token(), null).status()).as("public once approved").isEqualTo(200);
    }

    @Test
    void theOwnersRoleDoesNotGiveAccessToOtherPeoplesInterviews() throws Exception {
        assertThat(call("GET", "/api/sessions/" + sessionA, owner.token(), null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/sessions/" + sessionA + "/events", owner.token(), null).body()).doesNotContain(A_SECRET);
    }

    @Test
    void theExportContainsOnlyTheCallersOwnData() throws Exception {
        assertThat(call("GET", "/api/account/export", a.token(), null).body()).contains(A_SECRET);
        assertThat(call("GET", "/api/account/export", b.token(), null).body()).doesNotContain(A_SECRET).doesNotContain(a.email());
    }

    // ------------------------------------------------------------------ C2: owner spoofing

    @Test
    void aCaseVariantOfTheOwnersEmailCannotBeRegisteredOrGainOwnerRights() throws Exception {
        Resp r = call("POST", "/api/auth/signup", null,
                "{\"email\":\"" + ownerEmail.toUpperCase() + "\",\"password\":\"Passw0rd1\",\"displayName\":\"x\"}");
        assertThat(r.status()).isEqualTo(409);
        Resp padded = call("POST", "/api/auth/signup", null,
                "{\"email\":\" " + ownerEmail + " \",\"password\":\"Passw0rd1\",\"displayName\":\"x\"}");
        assertThat(padded.status()).isIn(400, 409);
        assertThat(call("GET", "/api/questions/pending", a.token(), null).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ C3: mass assignment

    @Test
    void fieldsThatMustNotBeSettableAreIgnoredOnEveryWriteEndpoint() throws Exception {
        // signup: cannot pick an id or claim to be the owner
        UUID wantedId = UUID.randomUUID();
        Resp s = call("POST", "/api/auth/signup", null, "{\"email\":\"mass-" + UUID.randomUUID() + "@example.com\",\"password\":\"Passw0rd1\","
                + "\"displayName\":\"m\",\"isOwner\":true,\"owner\":true,\"role\":\"OWNER\",\"id\":\"" + wantedId + "\",\"userId\":\"" + wantedId + "\"}");
        assertThat(s.status()).isEqualTo(201);
        assertThat(s.json().get("isOwner").asBoolean()).isFalse();
        assertThat(s.json().get("userId").asString()).isNotEqualTo(wantedId.toString());
        String massToken = s.json().get("token").asString();
        assertThat(call("GET", "/api/questions/pending", massToken, null).status()).isEqualTo(403);

        // question: cannot publish itself, impersonate a submitter, or attach a "vetted" reference
        Resp q = call("POST", "/api/questions", b.token(), "{\"title\":\"mass\",\"prompt\":\"p\",\"status\":\"APPROVED\",\"source\":\"SEED\","
                + "\"submittedBy\":\"" + a.id() + "\",\"referenceSolution\":{\"summary\":\"planted\"},\"hasVettedReference\":true}");
        assertThat(q.status()).isEqualTo(202);
        assertThat(q.json().get("status").asString()).isEqualTo("PENDING");
        assertThat(q.json().get("source").asString()).isEqualTo("COMMUNITY");
        assertThat(q.json().get("hasVettedReference").asBoolean()).isFalse();
        UUID qid = UUID.fromString(q.json().get("id").asString());
        assertThat(call("GET", "/api/questions", null, null).body()).as("not in the public bank").doesNotContain(qid.toString());
        assertThat(call("GET", "/api/questions/" + qid, a.token(), null).status()).as("not attributed to A").isEqualTo(404);

        // session: cannot be created for someone else
        Resp sess = call("POST", "/api/sessions", b.token(), "{\"questionId\":\"" + question + "\",\"userId\":\"" + a.id() + "\",\"state\":\"ENDED\"}");
        assertThat(sess.status()).isEqualTo(201);
        assertThat(sess.json().get("state").asString()).isEqualTo("ACTIVE");
        UUID created = UUID.fromString(sess.json().get("id").asString());
        assertThat(call("GET", "/api/sessions/" + created, b.token(), null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/sessions/" + created, a.token(), null).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ C3 / C5: WebSocket

    @Test
    void theSocketEnforcesOwnershipTicketsAndOrigin() throws Exception {
        UUID sessionB = createSession(b);
        String bTicketForB = call("POST", "/api/sessions/" + sessionB + "/ws-ticket", b.token(), null).json().get("ticket").asString();
        assertThat(staysOpen(sessionB.toString(), bTicketForB, ORIGIN)).as("B on B's own session").isTrue();

        // B's ticket (valid, but for B's session) used against A's session
        String bTicket2 = call("POST", "/api/sessions/" + sessionB + "/ws-ticket", b.token(), null).json().get("ticket").asString();
        assertThat(staysOpen(sessionA.toString(), bTicket2, ORIGIN)).as("B's ticket on A's session").isFalse();

        // the login token is not a ticket; no ticket; a garbage ticket
        assertThat(staysOpen(sessionA.toString(), a.token(), ORIGIN)).as("login token as ticket").isFalse();
        assertThat(staysOpen(sessionA.toString(), "", ORIGIN)).as("no ticket").isFalse();
        assertThat(staysOpen(sessionA.toString(), "not.a.ticket", ORIGIN)).as("garbage").isFalse();

        // a perfectly good ticket from a foreign origin
        String aTicket = call("POST", "/api/sessions/" + sessionA + "/ws-ticket", a.token(), null).json().get("ticket").asString();
        assertThat(staysOpen(sessionA.toString(), aTicket, "https://evil.example.net")).as("foreign origin").isFalse();
        // (a refused handshake must not have used the ticket up for the right origin... and a good one works once)
        String aTicket2 = call("POST", "/api/sessions/" + sessionA + "/ws-ticket", a.token(), null).json().get("ticket").asString();
        assertThat(staysOpen(sessionA.toString(), aTicket2, ORIGIN)).isTrue();
        assertThat(staysOpen(sessionA.toString(), aTicket2, ORIGIN)).as("second use of the same ticket").isFalse();
    }

    @Test
    void aFrameNamingAnotherSessionCannotWriteIntoIt() throws Exception {
        UUID sessionB = createSession(b);
        String ticket = call("POST", "/api/sessions/" + sessionB + "/ws-ticket", b.token(), null).json().get("ticket").asString();
        WsClient ws = WsClient.connect(port, sessionB, ticket);
        // B's socket, but the frame claims A's session id
        ws.send("{\"type\":\"transcript.final\",\"sessionId\":\"" + sessionA + "\",\"text\":\"forged-by-b-9981\",\"ts\":1}");
        ws.send("{\"type\":\"chat.message\",\"sessionId\":\"" + sessionA + "\",\"sender\":\"ai\",\"text\":\"forged-ai-9982\",\"ts\":1}");
        ws.awaitAndClose();
        String aEvents = call("GET", "/api/sessions/" + sessionA + "/events", a.token(), null).body();
        assertThat(aEvents).doesNotContain("forged-by-b-9981").doesNotContain("forged-ai-9982");
    }

    // ------------------------------------------------------------------ pooling

    @Test
    void underHeavyInterleavingOnATinyPoolNoRequestEverSeesAnotherUsersData() throws Exception {
        // 3 connections, many concurrent requests alternating between users and anonymous callers: if a
        // transaction ever inherited the previous request's identity, some response would contain foreign data.
        UUID sessionB = createSession(b);
        int threads = 8;
        int rounds = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<String>>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int offset = t;
            results.add(pool.submit(() -> {
                List<String> leaks = new ArrayList<>();
                for (int i = 0; i < rounds; i++) {
                    switch ((i + offset) % 3) {
                        case 0 -> {
                            String body = call("GET", "/api/sessions", a.token(), null).body();
                            if (body.contains(sessionB.toString())) leaks.add("A saw B's session");
                            if (!body.contains(sessionA.toString())) leaks.add("A could not see its own session");
                        }
                        case 1 -> {
                            String body = call("GET", "/api/sessions", b.token(), null).body();
                            if (body.contains(sessionA.toString())) leaks.add("B saw A's session");
                            if (!body.contains(sessionB.toString())) leaks.add("B could not see its own session");
                            if (call("GET", "/api/sessions/" + sessionA + "/events", b.token(), null).status() != 404) leaks.add("B read A's events");
                        }
                        default -> {
                            String body = call("GET", "/api/questions", null, null).body();
                            if (body.contains("\"PENDING\"")) leaks.add("anonymous saw an unpublished question");
                            if (call("GET", "/api/sessions/" + sessionA, null, null).status() != 403) leaks.add("anonymous reached a session");
                        }
                    }
                }
                return leaks;
            }));
        }
        List<String> all = new ArrayList<>();
        for (Future<List<String>> f : results) {
            all.addAll(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(all).isEmpty();
    }

    // ------------------------------------------------------------------ errors do not leak internals

    @Test
    void errorResponsesNeverExposeStackTracesOrInternals() throws Exception {
        List<Resp> responses = List.of(
                call("GET", "/api/sessions/not-a-uuid", a.token(), null),
                call("GET", "/api/sessions/" + UUID.randomUUID() + "/events", a.token(), null),
                call("POST", "/api/sessions", a.token(), "{\"questionId\":\"nope\"}"),
                call("POST", "/api/sessions", a.token(), "{"),
                call("POST", "/api/auth/login", null, "[]"),
                call("GET", "/api/nope", a.token(), null),
                call("PUT", "/api/sessions", a.token(), "{}"));
        for (Resp r : responses) {
            assertThat(r.status()).isBetween(400, 499);
            assertThat(r.body()).doesNotContain("Exception").doesNotContain("\tat ").doesNotContain("org.springframework")
                    .doesNotContain("com.ossature").doesNotContain("SQL").doesNotContain("hibernate");
        }
        Map<String, List<String>> headers = responses.get(0).raw().headers().map();
        assertThat(headers.keySet().stream().map(String::toLowerCase)).doesNotContain("server", "x-powered-by");
    }
}
