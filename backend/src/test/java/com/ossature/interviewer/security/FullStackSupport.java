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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Shared set-up for the end-to-end security tests: a real server on a real port, a real database as the
 * restricted role, real signed-in users (two ordinary ones and the owner), one approved question and one
 * interview of A's with a private message in it. Every subclass shares one Spring context and one database.
 * Row-level security and the controllers' own checks are both active, as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "ossature.auth.owner-emails=the-owner@example.com",
        "ossature.cors.allowed-origins=http://localhost:5173",
        "ossature.rate-limit.signup-max-per-ip=200",
        "ossature.rate-limit.login-max-per-ip=1000",
        "ossature.rate-limit.session-create-max=100",
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
abstract class FullStackSupport {

    static final ScratchDatabase DB = ScratchDatabase.available() ? ScratchDatabase.create() : null;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ORIGIN = "http://localhost:5173";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("spring.datasource.username", () -> ScratchDatabase.APP_USER);
        registry.add("spring.datasource.password", () -> ScratchDatabase.APP_PASSWORD);
        registry.add("spring.flyway.url", DB::url);
        registry.add("spring.flyway.user", DB::ownerUser);
        registry.add("spring.flyway.password", DB::ownerPassword);
    }

    static {
        if (DB != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(DB::close));
        }
    }

    @LocalServerPort int port;
    @Value("${ossature.auth.owner-emails}") String ownerEmail;

    final HttpClient http = HttpClient.newHttpClient();

    record Resp(int status, String body, HttpResponse<String> raw) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    record User(String email, UUID id, String token) {}

    User a, b, owner;
    UUID question;     // an approved question both can run
    UUID sessionA;     // A's interview, with a private message in it
    static final String A_SECRET = "a-private-sentence-4711";

    // ------------------------------------------------------------------ plumbing

    Resp call(String method, String path, String token, String jsonBody, String... extraHeaders) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        boolean originOverridden = false;
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            originOverridden |= "Origin".equalsIgnoreCase(extraHeaders[i]);
        }
        if (!originOverridden) {
            rb.header("Origin", ORIGIN);
        }
        if (token != null) {
            rb.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            rb.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        rb.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
        HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r);
    }

    User signup(String tag) throws Exception {
        String email = tag + "-" + UUID.randomUUID() + "@example.com";
        return signupWith(email);
    }

    User signupWith(String email) throws Exception {
        Resp r = call("POST", "/api/auth/signup", null,
                "{\"email\":\"" + email + "\",\"password\":\"Passw0rd1\",\"displayName\":\"t\"}");
        assertThat(r.status()).as("signup " + email + ": " + r.body()).isEqualTo(201);
        return new User(email, UUID.fromString(r.json().get("userId").asString()), r.json().get("token").asString());
    }

    /** The owner account is shared by every test class on this database: register it once, then just sign in. */
    User ownerSession() throws Exception {
        Resp signup = call("POST", "/api/auth/signup", null,
                "{\"email\":\"" + ownerEmail + "\",\"password\":\"Passw0rd1\",\"displayName\":\"owner\"}");
        Resp r = signup.status() == 201 ? signup : call("POST", "/api/auth/login", null,
                "{\"email\":\"" + ownerEmail + "\",\"password\":\"Passw0rd1\"}");
        assertThat(r.status()).as(r.body()).isIn(200, 201);
        return new User(ownerEmail, UUID.fromString(r.json().get("userId").asString()), r.json().get("token").asString());
    }

    UUID createSession(User u) throws Exception {
        Resp r = call("POST", "/api/sessions", u.token(), "{\"questionId\":\"" + question + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asString());
    }

    @BeforeAll
    void setUpUsers() throws Exception {
        a = signup("a");
        b = signup("b");
        owner = ownerSession();
        question = UUID.fromString(call("GET", "/api/questions", null, null).json().get(0).get("id").asString());
        sessionA = createSession(a);
        // a private message in A's session, via the real WebSocket
        String ticket = call("POST", "/api/sessions/" + sessionA + "/ws-ticket", a.token(), null).json().get("ticket").asString();
        WsClient ws = WsClient.connect(port, sessionA, ticket);
        ws.send("{\"type\":\"transcript.final\",\"sessionId\":\"" + sessionA + "\",\"text\":\"" + A_SECRET + "\",\"ts\":1}");
        ws.awaitAndClose();
    }

    // ------------------------------------------------------------------ minimal WebSocket client

    static final class WsClient implements WebSocket.Listener {
        final List<String> received = new ArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile int closeCode = -1;
        WebSocket socket;

        static WsClient connect(int port, UUID session, String ticket) throws Exception {
            return connectAs(port, session.toString(), ticket, ORIGIN);
        }

        static WsClient connectAs(int port, String sessionId, String ticket, String origin) throws Exception {
            WsClient c = new WsClient();
            c.socket = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", origin)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/interview?sessionId=" + sessionId + "&lastSeq=0&ticket=" + ticket), c)
                    .get(10, TimeUnit.SECONDS);
            return c;
        }

        void send(String text) throws Exception {
            socket.sendText(text, true).get(5, TimeUnit.SECONDS);
        }

        void awaitAndClose() throws Exception {
            Thread.sleep(600);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            closed.await(5, TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            received.add(data.toString());
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closeCode = code;
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.countDown();
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }
    }

    /** True if the server accepted the connection and kept it open (as opposed to refusing or closing it). */
    boolean staysOpen(String sessionId, String ticket, String origin) {
        try {
            WsClient c = WsClient.connectAs(port, sessionId, ticket, origin);
            boolean closedQuickly = c.closed.await(1500, TimeUnit.MILLISECONDS);
            if (!closedQuickly) {
                c.socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS);
            }
            return !closedQuickly;
        } catch (Exception refusedAtHandshake) {
            return false;
        }
    }
}
