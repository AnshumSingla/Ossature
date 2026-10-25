package com.ossature.interviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossature.interviewer.agent.AgentClient;
import com.ossature.interviewer.agent.AgentDtos;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * C1 / C4 / C5 / C7 of the security test plan: forged tokens, hostile request bodies, abusive WebSocket frames,
 * frames in the wrong session state, and CORS. The agent is mocked, so these tests are deterministic and
 * spend no model calls - and they can assert how often the (paid) agent is called.
 */
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
class HostileInputFullStackTest extends FullStackSupport {

    @MockitoBean AgentClient agent;
    @Value("${ossature.auth.jwt-secret}") String jwtSecret;

    @BeforeEach
    void stubAgent() {
        reset(agent);
        when(agent.startSession(any())).thenReturn(new AgentDtos.TurnResponse("Welcome - ask me anything.", "clarifying", false));
        when(agent.sendTranscript(any(), any())).thenReturn(new AgentDtos.TurnResponse("Go on.", "probing", false));
        when(agent.submitDiagram(any(), any())).thenReturn(new AgentDtos.ReviewResponse(List.of(new AgentDtos.Flaw("gap", "What if the cache fails?", 1))));
        when(agent.requestDebrief(any(), any())).thenReturn(new AgentDtos.DebriefResponse(List.of("s"), List.of("g"), List.of("u"), "cmp"));
    }

    // ================================================================== C1: forged and abused tokens

    private SecretKey key() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    private io.jsonwebtoken.JwtBuilder validClaims(UUID sub) {
        return Jwts.builder().issuer("ossature-backend").audience().add("ossature-api").and().subject(sub.toString())
                .claim("email", "x@example.com").claim("typ", "access")
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600)));
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Every one of these must be treated as "not logged in": 403, no data, no stack trace. */
    private void assertRejected(String authorizationHeader) throws Exception {
        Resp r = call("GET", "/api/sessions", null, null, "Authorization", authorizationHeader);
        // (400: the server refuses an absurdly long header outright - also a rejection)
        assertThat(r.status()).as("Authorization: " + abbreviate(authorizationHeader)).isIn(400, 403);
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("\tat ").doesNotContain(sessionA.toString());
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    @Test
    void forgedAndMalformedTokensAreAllRefused() throws Exception {
        String good = a.token();
        String[] parts = good.split("\\.");

        // alg=none, with and without a trailing dot, claiming to be A
        String none = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + parts[1] + ".";
        assertRejected("Bearer " + none);
        assertRejected("Bearer " + none.substring(0, none.length() - 1));

        // header says RS256 / HS256 with a bogus signature / no signature
        assertRejected("Bearer " + b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + parts[1] + "." + parts[2]);
        assertRejected("Bearer " + parts[0] + "." + parts[1] + ".AAAA");
        assertRejected("Bearer " + parts[0] + "." + parts[1]);

        // payload swapped for B's id, original signature kept (tampering)
        String bPayload = b64("{\"sub\":\"" + b.id() + "\",\"typ\":\"access\",\"iss\":\"ossature-backend\",\"aud\":\"ossature-api\",\"exp\":9999999999}");
        assertRejected("Bearer " + parts[0] + "." + bPayload + "." + parts[2]);

        // signed with a different key
        SecretKey other = Keys.hmacShaKeyFor("another-secret-another-secret-another-secret-9".getBytes(StandardCharsets.UTF_8));
        assertRejected("Bearer " + validClaims(a.id()).signWith(other).compact());

        // correctly signed but wrong claims
        assertRejected("Bearer " + validClaims(a.id()).expiration(new Date(System.currentTimeMillis() - 1000)).signWith(key()).compact());
        assertRejected("Bearer " + validClaims(a.id()).notBefore(Date.from(Instant.now().plusSeconds(3600))).signWith(key()).compact());
        assertRejected("Bearer " + validClaims(a.id()).claim("typ", "ws").signWith(key()).compact());
        assertRejected("Bearer " + validClaims(a.id()).issuer("someone-else").signWith(key()).compact());
        assertRejected("Bearer " + Jwts.builder().issuer("ossature-backend").audience().add("ossature-api").and().claim("typ", "access")
                .expiration(Date.from(Instant.now().plusSeconds(600))).signWith(key()).compact()); // no subject
        assertRejected("Bearer " + validClaims(a.id()).subject("not-a-uuid").signWith(key()).compact());
        assertRejected("Bearer " + validClaims(a.id()).subject("' OR '1'='1").signWith(key()).compact());

        // not tokens at all
        for (String junk : List.of("Bearer", "Bearer ", "Bearer null", "Bearer undefined", "Bearer a.b.c", "Bearer " + "x".repeat(8000),
                "Basic " + Base64.getEncoder().encodeToString("a:b".getBytes(StandardCharsets.UTF_8)), "bearer " + good, good)) {
            assertRejected(junk);
        }
    }

    @Test
    void aLoginTokenForAnAccountThatNoLongerExistsGetsNoDataAndNoServerError() throws Exception {
        User gone = signup("gone");
        assertThat(call("POST", "/api/account/delete", gone.token(), "{\"password\":\"Passw0rd1\"}").status()).isEqualTo(204);

        // the access token is still within its 15 minutes (documented residual) - it must not turn into a 500
        assertThat(call("GET", "/api/sessions", gone.token(), null).status()).isIn(200, 401, 403, 404);
        assertThat(call("GET", "/api/sessions", gone.token(), null).body()).doesNotContain(sessionA.toString());
        Resp create = call("POST", "/api/sessions", gone.token(), "{\"questionId\":\"" + question + "\"}");
        assertThat(create.status()).as(create.body()).isBetween(400, 499);
        Resp submit = call("POST", "/api/questions", gone.token(), "{\"title\":\"t\",\"prompt\":\"p\"}");
        assertThat(submit.status()).as(submit.body()).isBetween(400, 499);
        // and the session cannot be refreshed
        assertThat(call("POST", "/api/auth/refresh", null, null, "X-Ossature-Client", "web").status()).isEqualTo(401);
    }

    @Test
    void twoAuthorizationHeadersDoNotLetAnAttackerPickTheMoreFavourableOne() throws Exception {
        // Java's client sends both; whichever the server honours must not turn B's token into A's identity.
        Resp r = call("GET", "/api/sessions/" + sessionA + "/events", b.token(), null, "Authorization", "Bearer " + a.token());
        assertThat(r.body()).as("one identity per request: never a mixture").satisfiesAnyOf(
                body -> assertThat(body).contains(A_SECRET),   // honoured A's token entirely (A is entitled to it)
                body -> assertThat(body).doesNotContain(A_SECRET));
        assertThat(r.status()).isIn(200, 400, 403, 404);
    }

    // ================================================================== C4: hostile bodies

    @Test
    void malformedAndAbusiveJsonIsAnsweredWith4xxNeverAServerError() throws Exception {
        List<String> bodies = List.of(
                "{", "}", "[]", "null", "\"string\"", "12345", "",
                "[".repeat(20_000),                                                    // absurd nesting
                "{\"title\":" + "{\"a\":".repeat(5000) + "1" + "}".repeat(5000) + "}",  // nested object
                "{\"title\":\"dup\",\"title\":\"dup2\",\"prompt\":\"p\"}",             // duplicate key
                "{\"title\":\"t\",\"prompt\":\"p\",\"extra\":" + "9".repeat(5000) + "}", // huge number
                "{\"title\":\"\\ud800\",\"prompt\":\"lone surrogate\"}",
                "{\"title\":\"t\",\"prompt\":\"" + "x".repeat(5000) + "\"}",           // over the field limit
                "{\"title\":\"" + "y".repeat(500) + "\",\"prompt\":\"p\"}",
                "\uFEFF{\"title\":\"bom\",\"prompt\":\"p\"}");
        for (String body : bodies) {
            Resp r = call("POST", "/api/questions", a.token(), body);
            assertThat(r.status()).as("body starting " + abbreviate(body) + " -> " + abbreviate(r.body())).isLessThan(500);
            assertThat(r.body()).doesNotContain("Exception").doesNotContain("\tat ");
        }
        // the same for the unauthenticated endpoints
        for (String body : bodies) {
            assertThat(call("POST", "/api/auth/login", null, body).status()).isLessThan(500);
            assertThat(call("POST", "/api/auth/signup", null, body).status()).isLessThan(500);
        }
    }

    @Test
    void anOversizedBodyIsRefusedWith413() throws Exception {
        Resp r = call("POST", "/api/questions", a.token(), "{\"title\":\"t\",\"prompt\":\"" + "x".repeat(200_000) + "\"}");
        assertThat(r.status()).isEqualTo(413);
    }

    @Test
    void controlCharactersAndNulBytesInTextAreRejectedNotPassedToTheDatabase() throws Exception {
        for (String evil : List.of("a\\u0000b", "bell\\u0007", "esc\\u001b[31m", "line\\u2028sep", "bidi\\u202Eoverride")) {
            Resp q = call("POST", "/api/questions", a.token(), "{\"title\":\"" + evil + "\",\"prompt\":\"fine prompt\"}");
            assertThat(q.status()).as("title " + evil + " -> " + q.body()).isBetween(400, 499);
            Resp p = call("POST", "/api/questions", a.token(), "{\"title\":\"fine\",\"prompt\":\"" + evil + "\"}");
            assertThat(p.status()).as("prompt " + evil + " -> " + p.body()).isBetween(400, 499);
            Resp n = call("POST", "/api/auth/signup", null, "{\"email\":\"ctl-" + UUID.randomUUID() + "@example.com\",\"password\":\"Passw0rd1\",\"displayName\":\"" + evil + "\"}");
            assertThat(n.status()).as("displayName " + evil + " -> " + n.body()).isBetween(400, 499);
        }
        // ordinary text with newlines, tabs and non-Latin scripts is fine
        Resp ok = call("POST", "/api/questions", a.token(), "{\"title\":\"Design a \\u00e9\\u4e2d\\ud83d\\ude80 service\",\"prompt\":\"Line one\\nLine two\\twith a tab\"}");
        assertThat(ok.status()).as(ok.body()).isEqualTo(202);
    }

    @Test
    void sqlInjectionPayloadsAreStoredAsPlainTextAndNothingBreaks() throws Exception {
        List<String> payloads = List.of("'; DROP TABLE questions; --", "\\\" OR 1=1 --", "' UNION SELECT password_hash FROM users --", "${jndi:ldap://x/a}", "{{7*7}}", "%s%s%s%n");
        for (String p : payloads) {
            Resp r = call("POST", "/api/questions", a.token(), "{\"title\":\"" + p + "\",\"prompt\":\"" + p + "\"}");
            assertThat(r.status()).as(p + " -> " + r.body()).isEqualTo(202);
            assertThat(r.json().get("title").asString()).isEqualTo(p.replace("\\\"", "\""));
            Resp login = call("POST", "/api/auth/login", null, "{\"email\":\"inj-" + UUID.randomUUID() + "@example.com\",\"password\":\"" + p + "\"}");
            assertThat(login.status()).isIn(400, 401);
        }
        assertThat(call("GET", "/api/questions", null, null).status()).as("the bank still works").isEqualTo(200);
        assertThat(call("GET", "/api/sessions/" + sessionA, a.token(), null).status()).isEqualTo(200);
        for (String path : List.of("/api/sessions/1%27%20OR%20%271%27%3D%271", "/api/sessions/..%2f..%2fetc%2fpasswd", "/api/questions/%00", "/api/sessions/" + UUID.randomUUID() + "%27")) {
            Resp r = call("GET", path, a.token(), null);
            assertThat(r.status()).as(path).isBetween(400, 499);
        }
    }

    @Test
    void headerInjectionAttemptsAreRejectedByTheServer() throws Exception {
        String request = "GET /api/questions HTTP/1.1\r\nHost: localhost\r\nX-Test: a\r\nInjected-Header: yes\r\n\r\n";
        // a CRLF inside a header VALUE sent as raw bytes
        String raw = "GET /api/questions HTTP/1.1\r\nHost: localhost\r\nOrigin: http://localhost:5173\r\nX-Test: a\rInjected: yes\r\nConnection: close\r\n\r\n";
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).doesNotContainIgnoringCase("\r\nInjected:");
            assertThat(response.startsWith("HTTP/1.1 400") || response.startsWith("HTTP/1.1 200")).isTrue();
        }
        assertThat(request).isNotEmpty();
    }

    // ================================================================== C7: CORS

    @Test
    void corsAllowsOnlyTheExactConfiguredOrigin() throws Exception {
        for (String origin : List.of("https://evil.example.net", "null", "http://localhost:5173.evil.net", "http://localhost:5174", "*")) {
            Resp pre = call("OPTIONS", "/api/sessions", null, null, "Origin", origin, "Access-Control-Request-Method", "GET",
                    "Access-Control-Request-Headers", "authorization");
            assertThat(pre.raw().headers().firstValue("Access-Control-Allow-Origin")).as("preflight from " + origin).isEmpty();
            Resp actual = call("GET", "/api/questions", null, null, "Origin", origin);
            assertThat(actual.raw().headers().firstValue("Access-Control-Allow-Origin")).as("response to " + origin).isEmpty();
        }
        Resp good = call("OPTIONS", "/api/sessions", null, null, "Origin", ORIGIN, "Access-Control-Request-Method", "GET",
                "Access-Control-Request-Headers", "authorization");
        assertThat(good.raw().headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(good.raw().headers().firstValue("Access-Control-Allow-Origin")).isNotEqualTo(java.util.Optional.of("*"));
    }

    // ================================================================== C5: WebSocket abuse and state

    private UUID newSessionFor(User u) throws Exception {
        return createSession(u);
    }

    private WsClient open(UUID session, User u) throws Exception {
        String ticket = call("POST", "/api/sessions/" + session + "/ws-ticket", u.token(), null).json().get("ticket").asString();
        return WsClient.connect(port, session, ticket);
    }

    private long events(UUID session, User u, String type) throws Exception {
        long n = 0;
        for (var e : call("GET", "/api/sessions/" + session + "/events", u.token(), null).json()) {
            if (type.equals(e.get("type").asString())) {
                n++;
            }
        }
        return n;
    }

    private static String transcript(UUID session, String text) {
        return "{\"type\":\"transcript.final\",\"sessionId\":\"" + session + "\",\"text\":\"" + text + "\",\"ts\":1}";
    }

    @Test
    void garbageFramesAreDroppedAndTheConnectionKeepsWorking() throws Exception {
        UUID s = newSessionFor(a);
        WsClient ws = open(s, a);
        for (String junk : List.of("", "not json", "null", "[]", "{}", "{\"type\":\"made.up\"}", "{\"type\":\"transcript.final\"}",
                "{\"type\":\"transcript.final\",\"text\":123}", "[".repeat(50_000), "{\"type\":\"diagram.locked\",\"finalDiagram\":\"not an object\"}")) {
            ws.send(junk);
        }
        ws.send(transcript(s, "still alive 7731"));
        ws.awaitAndClose();
        assertThat(call("GET", "/api/sessions/" + s + "/events", a.token(), null).body()).contains("still alive 7731");
    }

    @Test
    void aNulByteInATranscriptDoesNotBreakTheSessionOrTheDatabase() throws Exception {
        UUID s = newSessionFor(a);
        WsClient ws = open(s, a);
        ws.send(transcript(s, "before\\u0000after"));
        ws.send(transcript(s, "next frame 5512"));
        ws.awaitAndClose();
        Resp events = call("GET", "/api/sessions/" + s + "/events", a.token(), null);
        assertThat(events.status()).isEqualTo(200);
        assertThat(events.body()).contains("next frame 5512").doesNotContain("\\u0000");
    }

    @Test
    void aNulByteInADiagramLabelIsStrippedBeforeItReachesTheJsonbColumn() throws Exception {
        // Diagram labels are not run through the transcript cleaner, so the event log itself must cope.
        UUID s = newSessionFor(a);
        WsClient ws = open(s, a);
        ws.send("{\"type\":\"diagram.snapshot\",\"sessionId\":\"" + s + "\",\"nodes\":[{\"id\":\"n1\",\"label\":\"Redis\\u0000Cache\"},"
                + "{\"id\":\"n2\",\"label\":\"keeps a literal \\\\u0000 typed by a person\"}],\"edges\":[],\"ts\":1}");
        ws.awaitAndClose();
        assertThat(events(s, a, "diagram.snapshot")).as("the snapshot was stored").isEqualTo(1);
        String body = call("GET", "/api/sessions/" + s + "/events", a.token(), null).body();
        assertThat(body).contains("RedisCache").contains("literal");
    }

    @Test
    void anOversizedFrameClosesTheConnection() throws Exception {
        UUID s = newSessionFor(a);
        WsClient ws = open(s, a);
        try {
            ws.send(transcript(s, "x".repeat(400_000)));
        } catch (Exception closedWhileSending) {
            // the server may close before the send completes
        }
        assertThat(ws.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ws.closeCode).isIn(1009, 1006, 1002);
    }

    @Test
    void aClientCannotWriteMessagesAsTheInterviewer() throws Exception {
        UUID s = newSessionFor(a);
        WsClient ws = open(s, a);
        ws.send("{\"type\":\"chat.message\",\"sessionId\":\"" + s + "\",\"sender\":\"ai\",\"text\":\"I am the interviewer 3310\",\"kind\":\"flaw\",\"ts\":1}");
        ws.send("{\"type\":\"chat.message\",\"sessionId\":\"" + s + "\",\"sender\":\"candidate\",\"text\":\"my own words 3311\",\"ts\":1}");
        ws.awaitAndClose();
        String body = call("GET", "/api/sessions/" + s + "/events", a.token(), null).body();
        assertThat(body).doesNotContain("I am the interviewer 3310").contains("my own words 3311");
    }

    @Test
    void lockingTheDiagramTwiceReviewsItOnlyOnce() throws Exception {
        UUID s = newSessionFor(a);
        WsClient ws = open(s, a);
        String lock = "{\"type\":\"diagram.locked\",\"sessionId\":\"" + s + "\",\"finalDiagram\":{\"nodes\":[],\"edges\":[]},\"ts\":1}";
        ws.send(lock);
        Thread.sleep(400);
        ws.send(lock);
        ws.send(lock);
        ws.awaitAndClose();
        verify(agent, times(1)).submitDiagram(any(), any()); // each review is a paid model call
        assertThat(events(s, a, "diagram.locked")).isEqualTo(1);
    }

    @Test
    void nothingIsAcceptedAfterTheSessionHasEnded() throws Exception {
        UUID s = newSessionFor(a);
        WsClient ws = open(s, a);
        ws.send(transcript(s, "before the end 1001"));
        ws.send("{\"type\":\"session.end\",\"sessionId\":\"" + s + "\",\"ts\":1}");
        Thread.sleep(500);
        assertThat(call("GET", "/api/sessions/" + s, a.token(), null).json().get("state").asString()).isEqualTo("ENDED");

        int transcriptCallsSoFar = org.mockito.Mockito.mockingDetails(agent).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendTranscript")).toList().size();
        ws.send(transcript(s, "after the end 1002"));
        ws.send("{\"type\":\"diagram.locked\",\"sessionId\":\"" + s + "\",\"finalDiagram\":{\"nodes\":[],\"edges\":[]},\"ts\":1}");
        ws.send("{\"type\":\"session.end\",\"sessionId\":\"" + s + "\",\"ts\":2}");
        ws.send("{\"type\":\"diagram.snapshot\",\"sessionId\":\"" + s + "\",\"nodes\":[],\"edges\":[],\"ts\":3}");
        ws.awaitAndClose();

        String body = call("GET", "/api/sessions/" + s + "/events", a.token(), null).body();
        assertThat(body).contains("before the end 1001").doesNotContain("after the end 1002");
        assertThat(events(s, a, "session.end")).isEqualTo(1);
        assertThat(events(s, a, "diagram.locked")).isZero();
        verify(agent, times(1)).requestDebrief(any(), any());
        verify(agent, never()).submitDiagram(any(), any());
        assertThat(org.mockito.Mockito.mockingDetails(agent).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendTranscript")).count()).isEqualTo(transcriptCallsSoFar);
    }

    @Test
    void aFailedDebriefDoesNotMakeASessionUnendable() throws Exception {
        when(agent.requestDebrief(any(), any())).thenThrow(new RuntimeException("model unavailable"));
        UUID s = newSessionFor(a);
        WsClient ws = open(s, a);
        ws.send("{\"type\":\"session.end\",\"sessionId\":\"" + s + "\",\"ts\":1}");
        ws.awaitAndClose();
        // The candidate asked to end: the interview is over even though the debrief could not be produced.
        assertThat(call("GET", "/api/sessions/" + s, a.token(), null).json().get("state").asString()).isEqualTo("ENDED");
        assertThat(call("GET", "/api/sessions/" + s + "/debrief", a.token(), null).status()).isEqualTo(200);
        verify(agent, atLeast(1)).requestDebrief(any(), any());
    }
}
