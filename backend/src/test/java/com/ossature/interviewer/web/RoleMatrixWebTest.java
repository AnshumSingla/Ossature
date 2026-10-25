package com.ossature.interviewer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.config.RefreshProperties;
import com.ossature.interviewer.repo.InterimSummaryRepository;
import com.ossature.interviewer.security.JwtAuthFilter;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.security.SecurityConfig;
import com.ossature.interviewer.security.WsTicketService;
import com.ossature.interviewer.service.AccountService;
import com.ossature.interviewer.service.AuthService;
import com.ossature.interviewer.service.InterviewFlowService;
import com.ossature.interviewer.service.QuestionBankService;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.RefreshTokenService;
import com.ossature.interviewer.service.SessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * C2 of the security test plan: who may call what. One row per endpoint, one column per kind of caller, and a
 * completeness guard: every route the application actually maps must have a row, so a new endpoint cannot ship
 * without someone deciding (here) who is allowed to use it.
 *
 * <p>"Allowed" means the security layer lets the request through (the controller may still answer 404, 400, ...);
 * "denied" means it is stopped with 403 before reaching a controller. Ownership of individual records is a
 * separate question, covered by the tenant-isolation tests.
 */
@WebMvcTest
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, OwnerPolicy.class, RateLimiter.class,
        RefreshCookies.class, WsTicketService.class, RequestSizeLimitFilter.class, RoleMatrixWebTest.Props.class})
@TestPropertySource(properties = {
        "ossature.auth.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "ossature.auth.jwt-expiry=15m",
        "ossature.auth.owner-emails=owner@x.com",
        "ossature.cors.allowed-origins=https://app.example.com",
        "ossature.refresh.cookie-secure=true",
        "ossature.limits.max-request-bytes=65536",
        "ossature.limits.ws-max-message-bytes=262144",
        "ossature.limits.transcript-max-chars=4000",
        "ossature.limits.transcript-max-per-minute=30",
        "ossature.rate-limit.login-max-per-email-and-ip=1000",
        "ossature.rate-limit.login-email-ip-window=5m",
        "ossature.rate-limit.login-max-per-email-global=1000",
        "ossature.rate-limit.login-email-global-window=1h",
        "ossature.rate-limit.login-max-per-ip=1000",
        "ossature.rate-limit.login-ip-window=5m",
        "ossature.rate-limit.signup-max-per-ip=1000",
        "ossature.rate-limit.signup-window=1h",
        "ossature.rate-limit.session-create-max=1000",
        "ossature.rate-limit.session-create-window=1h",
})
class RoleMatrixWebTest {

    @TestConfiguration
    @EnableConfigurationProperties({AppProperties.class, RefreshProperties.class})
    static class Props {}

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired WsTicketService tickets;
    @Autowired RequestMappingHandlerMapping mappings;
    @MockitoBean AuthService auth;
    @MockitoBean RefreshTokenService refreshTokens;
    @MockitoBean AccountService accounts;
    @MockitoBean SessionService sessions;
    @MockitoBean InterimSummaryRepository summaries;
    @MockitoBean InterviewFlowService flow;
    @MockitoBean QuestionBankService bank;

    /** Who may call: anonymous, any signed-in user, the owner. */
    record Route(HttpMethod method, String template, boolean anonymous, boolean user, boolean owner) {}

    private static Route open(HttpMethod m, String t) {
        return new Route(m, t, true, true, true);
    }

    private static Route signedIn(HttpMethod m, String t) {
        return new Route(m, t, false, true, true);
    }

    private static Route ownerOnly(HttpMethod m, String t) {
        return new Route(m, t, false, false, true);
    }

    static final List<Route> MATRIX = List.of(
            // accounts and login sessions: public entry points (they authenticate by credentials or by cookie themselves)
            open(HttpMethod.POST, "/api/auth/signup"),
            open(HttpMethod.POST, "/api/auth/login"),
            open(HttpMethod.POST, "/api/auth/refresh"),
            open(HttpMethod.POST, "/api/auth/logout"),
            // the question bank: reading is public; submitting needs an account; moderating is the owner's
            open(HttpMethod.GET, "/api/questions"),
            open(HttpMethod.GET, "/api/questions/{id}"),
            signedIn(HttpMethod.POST, "/api/questions"),
            ownerOnly(HttpMethod.GET, "/api/questions/pending"),
            ownerOnly(HttpMethod.POST, "/api/questions/{id}/approve"),
            ownerOnly(HttpMethod.POST, "/api/questions/{id}/reject"),
            // interviews: signed-in users, each only their own (checked per record elsewhere)
            signedIn(HttpMethod.POST, "/api/sessions"),
            signedIn(HttpMethod.GET, "/api/sessions"),
            signedIn(HttpMethod.GET, "/api/sessions/{id}"),
            signedIn(HttpMethod.DELETE, "/api/sessions/{id}"),
            signedIn(HttpMethod.GET, "/api/sessions/{id}/debrief"),
            signedIn(HttpMethod.GET, "/api/sessions/{id}/summaries"),
            signedIn(HttpMethod.GET, "/api/sessions/{id}/diagram"),
            signedIn(HttpMethod.GET, "/api/sessions/{id}/events"),
            signedIn(HttpMethod.POST, "/api/sessions/{id}/ws-ticket"),
            // your own data
            signedIn(HttpMethod.GET, "/api/account/export"),
            signedIn(HttpMethod.POST, "/api/account/delete"),
            // operations
            open(HttpMethod.GET, "/actuator/health"),
            open(HttpMethod.GET, "/actuator/info"),
            ownerOnly(HttpMethod.GET, "/actuator/metrics"),
            ownerOnly(HttpMethod.GET, "/actuator/env"),
            ownerOnly(HttpMethod.GET, "/actuator/beans"));

    private String concrete(String template) {
        return template.replace("{id}", UUID.randomUUID().toString());
    }

    private int statusFor(Route r, String authorization) throws Exception {
        var req = request(r.method(), concrete(r.template())).contentType("application/json").content("{}");
        req.header("X-Ossature-Client", "web"); // the CSRF marker, so cookie endpoints reach their own logic
        if (authorization != null) {
            req.header("Authorization", authorization);
        }
        try {
            return mvc.perform(req).andReturn().getResponse().getStatus();
        } catch (jakarta.servlet.ServletException controllerFailed) {
            return 500; // the request got past the security layer and a (mocked, empty) controller then failed
        }
    }

    private void check(String who, String authorization, java.util.function.Predicate<Route> mayCall) throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Route r : MATRIX) {
            int status = statusFor(r, authorization);
            // The security layer denies with 403. (A 401 is a controller's own answer, e.g. "no session cookie".)
            boolean stoppedBySecurity = status == 403;
            if (mayCall.test(r) == stoppedBySecurity) {
                wrong.add(r.method() + " " + r.template() + " as " + who + " -> " + status
                        + (mayCall.test(r) ? " (should be allowed)" : " (should be denied)"));
            }
        }
        assertThat(wrong).as("role matrix, " + who).isEmpty();
    }

    @Test
    void anonymousCallers() throws Exception {
        check("anonymous", null, Route::anonymous);
    }

    @Test
    void aSignedInUser() throws Exception {
        check("user", "Bearer " + jwt.issue(UUID.randomUUID(), "someone@x.com"), Route::user);
    }

    @Test
    void theOwner() throws Exception {
        check("owner", "Bearer " + jwt.issue(UUID.randomUUID(), "owner@x.com"), Route::owner);
    }

    @Test
    void aSignedInUserWithAnOwnerLookingEmailThatIsNotTheOwnersIsNotTheOwner() throws Exception {
        for (String lookalike : List.of("owner@x.com.evil.net", "wner@x.com", "owner@x.co", "owner+tag@x.com", "not-owner@x.com")) {
            check("lookalike " + lookalike, "Bearer " + jwt.issue(UUID.randomUUID(), lookalike), Route::user);
        }
    }

    @Test
    void aTokenOfTheWrongKindOpensNothing() throws Exception {
        String ticket = tickets.issue(UUID.randomUUID(), UUID.randomUUID());
        check("ws ticket as bearer", "Bearer " + ticket, Route::anonymous);
    }

    /** Every route the application maps must be classified above - the guard that keeps this matrix honest. */
    @Test
    void everyMappedEndpointHasARowInTheMatrix() {
        Set<String> known = new TreeSet<>();
        MATRIX.forEach(r -> known.add(r.method() + " " + r.template()));

        Set<String> unclassified = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            var patterns = info.getPathPatternsCondition() == null ? Set.<String>of()
                    : info.getPathPatternsCondition().getPatternValues();
            var methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (pattern.startsWith("/error")) {
                    continue; // the servlet container's error page
                }
                for (var m : methods.isEmpty() ? Set.of(org.springframework.web.bind.annotation.RequestMethod.GET) : methods) {
                    String key = m + " " + pattern;
                    if (!known.contains(key)) {
                        unclassified.add(key);
                    }
                }
            }
        }
        assertThat(unclassified).as("mapped endpoints with no row in RoleMatrixWebTest.MATRIX - decide who may call them").isEmpty();
    }

    /** ...and the other direction: no row for an endpoint that no longer exists. */
    @Test
    void everyRowInTheMatrixIsARealEndpoint() {
        Set<String> mapped = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (String p : info.getPathPatternsCondition().getPatternValues()) {
                info.getMethodsCondition().getMethods().forEach(m -> mapped.add(m + " " + p));
            }
        }
        List<String> stale = MATRIX.stream().map(r -> r.method() + " " + r.template())
                .filter(k -> !mapped.contains(k) && !k.contains("/actuator/")).toList();
        assertThat(stale).isEmpty();
    }
}
