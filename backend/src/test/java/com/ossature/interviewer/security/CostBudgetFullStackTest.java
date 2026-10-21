package com.ossature.interviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.ossature.interviewer.agent.AgentClient;
import com.ossature.interviewer.agent.AgentDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * C6: the cost controls trip. Each spoken turn and diagram review is a paid model call; one person gets a daily
 * budget (3 here) and the whole service an hourly circuit breaker (5 here). Counted through the shared limiter, so
 * the same limits hold across instances. The agent is mocked so the number of paid calls can be counted exactly.
 */
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
@TestPropertySource(properties = {
        "ossature.budget.user-daily-turns=3",
        "ossature.budget.global-hourly-turns=5",
})
class CostBudgetFullStackTest extends FullStackSupport {

    @MockitoBean AgentClient agent;

    @BeforeEach
    void freshBudgetAndStubbedAgent() throws Exception {
        reset(agent);
        when(agent.startSession(any())).thenReturn(new AgentDtos.TurnResponse("Welcome.", "clarifying", false));
        when(agent.sendTranscript(any(), any())).thenReturn(new AgentDtos.TurnResponse("Go on.", "probing", false));
        // other test classes share this database: start from an empty service-wide counter
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("llm:global".getBytes(StandardCharsets.UTF_8)));
        try (Connection c = DB.owner(); PreparedStatement s = c.prepareStatement("DELETE FROM rate_limits WHERE key_hash = ?")) {
            s.setString(1, hash);
            s.executeUpdate();
        }
    }

    private long paidTurns() {
        return mockingDetails(agent).getInvocations().stream().filter(i -> i.getMethod().getName().equals("sendTranscript")).count();
    }

    private WsClient connect(UUID session, User u) throws Exception {
        String ticket = call("POST", "/api/sessions/" + session + "/ws-ticket", u.token(), null).json().get("ticket").asString();
        return WsClient.connect(port, session, ticket);
    }

    private static String turn(UUID session, int n) {
        return "{\"type\":\"transcript.final\",\"sessionId\":\"" + session + "\",\"text\":\"turn " + n + "\",\"ts\":1}";
    }

    @Test
    void aPersonOverTheirDailyBudgetAndThenTheWholeServiceOverItsHourlyOneAreStopped() throws Exception {
        User one = signup("budget-one");
        User two = signup("budget-two");

        // one person: 5 turns attempted, 3 allowed, then told why
        UUID s1 = createSession(one);
        WsClient w1 = connect(s1, one);
        for (int i = 1; i <= 5; i++) {
            w1.send(turn(s1, i));
        }
        w1.awaitAndClose();
        assertThat(paidTurns()).as("model calls for the first person").isEqualTo(3);
        assertThat(String.join("\n", w1.received)).contains("usage limit");

        // another person is unaffected by that - until the service-wide breaker (5/hour in total) trips
        UUID s2 = createSession(two);
        WsClient w2 = connect(s2, two);
        for (int i = 1; i <= 3; i++) {
            w2.send(turn(s2, i));
        }
        w2.awaitAndClose();
        assertThat(paidTurns()).as("3 + 2 = the whole service's hourly allowance").isEqualTo(5);
        assertThat(String.join("\n", w2.received)).contains("very busy");

        // and the person who hit the daily limit stays stopped
        WsClient again = connect(s1, one);
        again.send(turn(s1, 99));
        again.awaitAndClose();
        assertThat(paidTurns()).isEqualTo(5);
    }
}
