package com.ossature.interviewer.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.repo.InterimSummaryRepository;
import com.ossature.interviewer.security.JwtAuthFilter;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.security.SecurityConfig;
import com.ossature.interviewer.security.WsTicketService;
import com.ossature.interviewer.service.InterviewFlowService;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.SessionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** H9: who may obtain a WebSocket ticket for a session. */
@WebMvcTest(controllers = SessionController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, OwnerPolicy.class, WsTicketService.class,
        RateLimiter.class, RequestSizeLimitFilter.class, WsTicketEndpointWebTest.Props.class})
@TestPropertySource(properties = {
        "ossature.auth.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "ossature.auth.jwt-expiry=1h",
        "ossature.auth.owner-emails=",
        "ossature.limits.max-request-bytes=65536",
        "ossature.limits.ws-max-message-bytes=262144",
        "ossature.limits.transcript-max-chars=4000",
        "ossature.limits.transcript-max-per-minute=30",
        "ossature.rate-limit.login-max-per-email-and-ip=5",
        "ossature.rate-limit.login-email-ip-window=5m",
        "ossature.rate-limit.login-max-per-email-global=100",
        "ossature.rate-limit.login-email-global-window=1h",
        "ossature.rate-limit.login-max-per-ip=20",
        "ossature.rate-limit.login-ip-window=5m",
        "ossature.rate-limit.signup-max-per-ip=10",
        "ossature.rate-limit.signup-window=1h",
        "ossature.rate-limit.session-create-max=10",
        "ossature.rate-limit.session-create-window=1h",
})
class WsTicketEndpointWebTest {

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class Props {}

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired WsTicketService tickets;
    @MockitoBean SessionService sessions;
    @MockitoBean InterimSummaryRepository summaries;
    @MockitoBean InterviewFlowService flow;

    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private String bearer(UUID user) {
        return "Bearer " + jwt.issue(user, user + "@x.com");
    }

    private void sessionOwnedByOwner() {
        InterviewSession s = org.mockito.Mockito.mock(InterviewSession.class);
        when(s.getId()).thenReturn(sessionId);
        when(s.getUserId()).thenReturn(owner);
        when(sessions.get(sessionId)).thenReturn(s);
    }

    @Test
    void theSessionOwnerGetsATicketThatWorksForThatSessionOnce() throws Exception {
        sessionOwnedByOwner();
        String body = mvc.perform(post("/api/sessions/" + sessionId + "/ws-ticket").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andExpect(jsonPath("$.expiresInSeconds").value(30))
                .andReturn().getResponse().getContentAsString();
        String ticket = body.replaceAll(".*\"ticket\":\"([^\"]+)\".*", "$1");

        org.assertj.core.api.Assertions.assertThat(tickets.redeem(ticket, sessionId.toString())).contains(owner);
        org.assertj.core.api.Assertions.assertThat(tickets.redeem(ticket, sessionId.toString())).isEmpty();
    }

    @Test
    void anotherUserCannotGetATicketForSomeoneElsesSession() throws Exception {
        sessionOwnedByOwner();
        mvc.perform(post("/api/sessions/" + sessionId + "/ws-ticket").header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCallersGetNothing() throws Exception {
        sessionOwnedByOwner();
        mvc.perform(post("/api/sessions/" + sessionId + "/ws-ticket")).andExpect(status().isForbidden());
    }

    @Test
    void aWebSocketTicketCannotBeUsedAsALoginToken() throws Exception {
        sessionOwnedByOwner();
        String ticket = tickets.issue(owner, sessionId);
        // Presented as a Bearer token on the REST API, it must authenticate nobody.
        mvc.perform(post("/api/sessions/" + sessionId + "/ws-ticket").header("Authorization", "Bearer " + ticket))
                .andExpect(status().isForbidden());
    }
}
