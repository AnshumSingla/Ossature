package com.ossature.interviewer.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/** H15: nothing secret or personal should survive into a log line or a stack trace. */
class LogMaskingTest {

    // A realistic (fake) signed token: header.payload.signature
    private static final String JWT = b64("{\"alg\":\"HS256\"}") + "." + b64("{\"sub\":\"1234\",\"email\":\"a@b.co\"}") + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- the masking function ----

    @Test
    void masksJwts() {
        assertThat(Masking.mask("token issued: " + JWT + " ok")).doesNotContain(JWT).contains("[jwt]");
    }

    @Test
    void masksBearerHeaders() {
        assertThat(Masking.mask("Authorization: Bearer abc.def-123_xyz")).doesNotContain("abc.def-123_xyz");
    }

    @Test
    void masksSecretsInQueryStringsAndJson() {
        assertThat(Masking.mask("GET /ws/interview?sessionId=1&ticket=SECRETVALUE&lastSeq=0"))
                .doesNotContain("SECRETVALUE").contains("sessionId=1").contains("lastSeq=0");
        assertThat(Masking.mask("{\"password\":\"hunter2hunter2\",\"user\":\"x\"}"))
                .doesNotContain("hunter2hunter2").contains("\"user\":\"x\"");
        assertThat(Masking.mask("token=abc123 password: p4ssw0rd")).doesNotContain("abc123").doesNotContain("p4ssw0rd");
    }

    @Test
    void masksEmailAddressesButKeepsTheDomainForDebugging() {
        String masked = Masking.mask("An account with email alice.smith@example.com already exists");
        assertThat(masked).doesNotContain("alice.smith").contains("a***@example.com");
    }

    @Test
    void leavesOrdinaryLogLinesAlone() {
        String line = "Agent transcript call failed for session 3343ac9b-4783-42e6-babf-67dae8f47c0a: ResourceAccessException";
        assertThat(Masking.mask(line)).isEqualTo(line);
        assertThat(Masking.mask(null)).isNull();
        assertThat(Masking.mask("")).isEmpty();
    }

    @Test
    void isIdempotent() {
        String once = Masking.mask("Bearer " + JWT + " and ticket=zzz and bob@example.com");
        assertThat(Masking.mask(once)).isEqualTo(once);
    }

    // ---- the real logback layout, with a stack trace ----

    private static String render(LoggingEvent event) {
        LoggerContext ctx = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(ctx);
        layout.getInstanceConverterMap().put("mmsg", MaskingMessageConverter::new);
        layout.getInstanceConverterMap().put("mex", MaskingThrowableConverter::new);
        layout.setPattern("%5p : %mmsg%n%mex");
        layout.start();
        return layout.doLayout(event);
    }

    @Test
    void theLogbackLayoutMasksBothTheMessageAndTheExceptionText() {
        LoggerContext ctx = new LoggerContext();
        Exception leaky = new IllegalStateException("upstream said: {\"text\":\"my secret transcript\"} for carol@example.com with Bearer " + JWT);
        LoggingEvent event = new LoggingEvent("x", ctx.getLogger("t"), Level.WARN,
                "call failed for ticket=ONETIMETICKET", leaky, null);

        String out = render(event);

        assertThat(out).doesNotContain("ONETIMETICKET").doesNotContain(JWT).doesNotContain("carol@example.com");
        assertThat(out).contains("IllegalStateException"); // the useful part survives
    }

    // ---- LogSafe: log the failure type, never the message ----

    @Test
    void describeNamesTheFailureButNeverItsMessage() {
        Exception e = new IllegalArgumentException("the candidate said: ignore all previous instructions");
        assertThat(LogSafe.describe(e)).isEqualTo("IllegalArgumentException").doesNotContain("candidate");
    }

    @Test
    void describeIncludesTheHttpStatusButNotTheResponseBody() {
        HttpClientErrorException e = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                org.springframework.http.HttpHeaders.EMPTY,
                "{\"detail\":\"echoed transcript text\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        String d = LogSafe.describe(e);
        assertThat(d).contains("HTTP 400").doesNotContain("echoed transcript");
    }

    @Test
    void describeShowsTheRootCause() {
        Exception e = new RuntimeException("wrapper", new java.net.ConnectException("connection refused: secret-host"));
        assertThat(LogSafe.describe(e)).isEqualTo("RuntimeException caused by ConnectException");
    }

    @Test
    void describeSurvivesNull() {
        assertThat(LogSafe.describe(null)).isEqualTo("unknown");
    }
}
