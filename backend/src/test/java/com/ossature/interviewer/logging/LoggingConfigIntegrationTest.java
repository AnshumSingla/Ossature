package com.ossature.interviewer.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * H15, end to end: boots the real application so Spring Boot loads {@code logback-spring.xml}, then
 * logs secrets the way a careless library might and checks what actually reached the console.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LoggingConfigIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger("com.ossature.interviewer.logging.Probe");

    @Test
    void theRunningApplicationMasksSecretsAndPersonalDataInConsoleOutput(CapturedOutput output) {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        log.warn("handshake failed for /ws/interview?sessionId=1&ticket=ONETIMETICKET by dave@example.com with Bearer {}", jwt);
        log.warn("boom", new IllegalStateException("remote said {\"password\":\"hunter2hunter2\"}"));

        assertThat(output.getOut())
                .contains("handshake failed for /ws/interview?sessionId=1")
                .doesNotContain("ONETIMETICKET")
                .doesNotContain("dave@example.com")
                .doesNotContain(jwt)
                .doesNotContain("hunter2hunter2")
                .contains("IllegalStateException"); // the exception is still logged, just masked
    }
}
