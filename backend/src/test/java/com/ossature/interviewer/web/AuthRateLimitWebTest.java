package com.ossature.interviewer.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.security.JwtAuthFilter;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.security.SecurityConfig;
import com.ossature.interviewer.service.AuthService;
import com.ossature.interviewer.service.InvalidCredentialsException;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.web.dto.AuthDtos.AuthResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * H8: login and signup throttling. Limits are tiny so each scenario runs in a few requests:
 * 3 attempts per (account, IP), 6 per account overall, 2 signups per IP.
 *
 * <p>The scenarios that matter are the attacker's: a stranger must not be able to use up the
 * real user's login budget, either by guessing from their own address or by sending signups.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, OwnerPolicy.class, RateLimiter.class,
        RequestSizeLimitFilter.class, AuthRateLimitWebTest.Props.class})
@TestPropertySource(properties = {
        "ossature.auth.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "ossature.auth.jwt-expiry=1h",
        "ossature.auth.owner-emails=owner@x.com",
        "ossature.limits.max-request-bytes=65536",
        "ossature.limits.ws-max-message-bytes=262144",
        "ossature.limits.transcript-max-chars=4000",
        "ossature.limits.transcript-max-per-minute=30",
        "ossature.rate-limit.login-max-per-email-and-ip=3",
        "ossature.rate-limit.login-email-ip-window=5m",
        "ossature.rate-limit.login-max-per-email-global=6",
        "ossature.rate-limit.login-email-global-window=1h",
        "ossature.rate-limit.login-max-per-ip=1000",
        "ossature.rate-limit.login-ip-window=5m",
        "ossature.rate-limit.signup-max-per-ip=2",
        "ossature.rate-limit.signup-window=1h",
        "ossature.rate-limit.session-create-max=10",
        "ossature.rate-limit.session-create-window=1h",
})
class AuthRateLimitWebTest {

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class Props {}

    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;

    // The RateLimiter is a singleton shared by every test in this class, so each test gets its own
    // account (JUnit makes a new instance per test) or budgets would leak between scenarios.
    private final String victim = "victim-" + UUID.randomUUID() + "@example.com";

    private ResultActions login(String email, String fromIp) throws Exception {
        // doThrow().when() form: re-stubbing with when(auth.login(..)) would call the stub and throw.
        doThrow(new InvalidCredentialsException()).when(auth).login(any());
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}")
                .with(req -> { req.setRemoteAddr(fromIp); return req; }));
    }

    private ResultActions signup(String email, String fromIp) throws Exception {
        doReturn(new AuthResponse("t", UUID.randomUUID(), email, "n", false)).when(auth).signup(any());
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd1\",\"displayName\":\"n\"}")
                .with(req -> { req.setRemoteAddr(fromIp); return req; }));
    }

    @Test
    void oneAddressIsStoppedAfterItsOwnBudgetIsUsed() throws Exception {
        for (int i = 0; i < 3; i++) login(victim, "10.0.0.1").andExpect(status().isUnauthorized());
        login(victim, "10.0.0.1").andExpect(status().isTooManyRequests());
    }

    @Test
    void aStrangerGuessingFromTheirOwnAddressCannotLockTheRealUserOut() throws Exception {
        for (int i = 0; i < 4; i++) login(victim, "10.0.0.66"); // attacker exhausts their budget
        login(victim, "10.0.0.66").andExpect(status().isTooManyRequests());

        // The real user, from a different address, is unaffected and still gets a normal answer.
        for (int i = 0; i < 3; i++) login(victim, "192.168.1.5").andExpect(status().isUnauthorized());
    }

    @Test
    void signingUpWithSomeonesEmailDoesNotUseUpTheirLoginBudget() throws Exception {
        // Attacker floods signup for the victim's address until the signup limit trips.
        signup(victim, "10.0.0.77").andExpect(status().isCreated());
        signup(victim, "10.0.0.77").andExpect(status().isCreated());
        signup(victim, "10.0.0.77").andExpect(status().isTooManyRequests());

        // The victim's own login budget is untouched (before the fix this was shared and exhausted).
        for (int i = 0; i < 3; i++) login(victim, "192.168.1.5").andExpect(status().isUnauthorized());
    }

    @Test
    void signupIsThrottledPerAddress() throws Exception {
        signup("a@example.com", "10.0.0.8").andExpect(status().isCreated());
        signup("b@example.com", "10.0.0.8").andExpect(status().isCreated());
        signup("c@example.com", "10.0.0.8").andExpect(status().isTooManyRequests());
        signup("c@example.com", "10.0.0.9").andExpect(status().isCreated()); // a different address is fine
    }

    @Test
    void caseVariantsOfTheEmailShareOneBudget() throws Exception {
        login(victim.toUpperCase(), "10.0.0.2").andExpect(status().isUnauthorized());
        login(victim, "10.0.0.2").andExpect(status().isUnauthorized());
        login(victim.substring(0, 1).toUpperCase() + victim.substring(1), "10.0.0.2").andExpect(status().isUnauthorized());
        login(victim, "10.0.0.2").andExpect(status().isTooManyRequests());
    }

    @Test
    void guessingSpreadAcrossManyAddressesIsStillBoundedPerAccount() throws Exception {
        // 3 + 3 attempts from two addresses reach the account-wide cap of 6...
        for (int i = 0; i < 3; i++) login(victim, "10.1.0.1").andExpect(status().isUnauthorized());
        for (int i = 0; i < 3; i++) login(victim, "10.1.0.2").andExpect(status().isUnauthorized());
        // ...so a third, fresh address is refused even on its very first try.
        login(victim, "10.1.0.3").andExpect(status().isTooManyRequests());
    }

    @Test
    void otherAccountsAreUnaffectedByOneAccountsBudget() throws Exception {
        for (int i = 0; i < 4; i++) login(victim, "10.0.0.4");
        login("someone-else-" + UUID.randomUUID() + "@example.com", "10.0.0.4").andExpect(status().isUnauthorized());
    }
}
