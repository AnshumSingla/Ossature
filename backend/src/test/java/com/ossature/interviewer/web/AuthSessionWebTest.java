package com.ossature.interviewer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.config.RefreshProperties;
import com.ossature.interviewer.security.JwtAuthFilter;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.security.SecurityConfig;
import com.ossature.interviewer.service.AuthService;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.RefreshTokenService;
import com.ossature.interviewer.service.RefreshTokenService.Issued;
import com.ossature.interviewer.web.dto.AuthDtos.AuthResponse;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * H10: the refresh-token cookie is httpOnly, SameSite=Strict, scoped to /api/auth and Secure; refresh and
 * logout (which authenticate by cookie alone) refuse a request that is not from our own web client.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, OwnerPolicy.class, RateLimiter.class,
        RequestSizeLimitFilter.class, AuthSessionWebTest.Props.class})
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
        "ossature.rate-limit.login-max-per-email-and-ip=100",
        "ossature.rate-limit.login-email-ip-window=5m",
        "ossature.rate-limit.login-max-per-email-global=100",
        "ossature.rate-limit.login-email-global-window=1h",
        "ossature.rate-limit.login-max-per-ip=1000",
        "ossature.rate-limit.login-ip-window=5m",
        "ossature.rate-limit.signup-max-per-ip=100",
        "ossature.rate-limit.signup-window=1h",
        "ossature.rate-limit.session-create-max=10",
        "ossature.rate-limit.session-create-window=1h",
})
class AuthSessionWebTest {

    @TestConfiguration
    @EnableConfigurationProperties({AppProperties.class, RefreshProperties.class})
    static class Props {}

    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean RefreshTokenService refreshTokens;

    private final UUID user = UUID.randomUUID();
    private static final String ORIGIN = "https://app.example.com";

    private AuthResponse authResponse() {
        return new AuthResponse("access-token", 900, user, "u@x.com", "U", false);
    }

    private Issued issued(boolean persistent) {
        return new Issued(user, "the-refresh-token", persistent, Instant.now().plusSeconds(3600));
    }

    private ResultActions login(boolean remember) throws Exception {
        doReturn(authResponse()).when(auth).login(any());
        doReturn(issued(remember)).when(refreshTokens).start(eq(user), anyBoolean());
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"u@x.com\",\"password\":\"Passw0rd1\",\"rememberMe\":" + remember + "}"));
    }

    private static String setCookie(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    // ------------------------------------------------------------------ the cookie

    @Test
    void loginSetsAnHttpOnlySecureStrictCookieScopedToTheAuthPath() throws Exception {
        ResultActions r = login(true).andExpect(status().isOk()).andExpect(jsonPath("$.token").value("access-token"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
        String cookie = setCookie(r);
        assertThat(cookie).startsWith("ossature_refresh=the-refresh-token")
                .contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/auth");
        assertThat(cookie).as("the refresh token never appears in the response body")
                .doesNotContain("access-token");
        assertThat(r.andReturn().getResponse().getContentAsString()).doesNotContain("the-refresh-token");
    }

    @Test
    void aRememberedLoginHasALifetimeAndAnUnrememberedOneEndsWithTheBrowser() throws Exception {
        assertThat(setCookie(login(true))).contains("Max-Age=");
        assertThat(setCookie(login(false))).doesNotContain("Max-Age=").doesNotContain("Expires=");
    }

    @Test
    void rememberMeDefaultsToOn() throws Exception {
        doReturn(authResponse()).when(auth).login(any());
        doReturn(issued(true)).when(refreshTokens).start(eq(user), anyBoolean());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"u@x.com\",\"password\":\"Passw0rd1\"}")).andExpect(status().isOk());
        verify(refreshTokens).start(user, true);
    }

    // ------------------------------------------------------------------ refresh

    @Test
    void refreshWithAValidCookieReturnsANewAccessTokenAndRotatesTheCookie() throws Exception {
        doReturn(Optional.of(new Issued(user, "rotated-token", true, Instant.now().plusSeconds(3600))))
                .when(refreshTokens).rotate("the-refresh-token");
        doReturn(Optional.of(authResponse())).when(auth).sessionFor(user);

        ResultActions r = mvc.perform(post("/api/auth/refresh").header("X-Ossature-Client", "web").header("Origin", ORIGIN)
                .cookie(new Cookie("ossature_refresh", "the-refresh-token")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value("access-token"));
        assertThat(setCookie(r)).startsWith("ossature_refresh=rotated-token").contains("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void refreshWithAnUnknownOrUsedTokenIs401AndClearsTheCookie() throws Exception {
        doReturn(Optional.empty()).when(refreshTokens).rotate("stolen");
        ResultActions r = mvc.perform(post("/api/auth/refresh").header("X-Ossature-Client", "web")
                .cookie(new Cookie("ossature_refresh", "stolen"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Your session has ended. Please log in again."));
        assertThat(setCookie(r)).startsWith("ossature_refresh=;").contains("Max-Age=0");
    }

    @Test
    void refreshWithNoCookieIs401() throws Exception {
        doReturn(Optional.empty()).when(refreshTokens).rotate(null);
        mvc.perform(post("/api/auth/refresh").header("X-Ossature-Client", "web")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ CSRF

    @Test
    void refreshAndLogoutRefuseARequestWithoutOurClientHeader() throws Exception {
        for (String path : new String[] {"/api/auth/refresh", "/api/auth/logout"}) {
            mvc.perform(post(path).cookie(new Cookie("ossature_refresh", "the-refresh-token"))).andExpect(status().isForbidden());
        }
        verify(refreshTokens, never()).rotate(any());
        verify(refreshTokens, never()).end(any());
    }

    @Test
    void refreshAndLogoutRefuseAForeignOrigin() throws Exception {
        for (String path : new String[] {"/api/auth/refresh", "/api/auth/logout"}) {
            mvc.perform(post(path).header("X-Ossature-Client", "web").header("Origin", "https://evil.example.net")
                    .cookie(new Cookie("ossature_refresh", "the-refresh-token"))).andExpect(status().isForbidden());
        }
        verify(refreshTokens, never()).rotate(any());
        verify(refreshTokens, never()).end(any());
    }

    // ------------------------------------------------------------------ logout

    @Test
    void logoutRevokesTheSessionOnTheServerAndClearsTheCookie() throws Exception {
        ResultActions r = mvc.perform(post("/api/auth/logout").header("X-Ossature-Client", "web").header("Origin", ORIGIN)
                .cookie(new Cookie("ossature_refresh", "the-refresh-token"))).andExpect(status().isNoContent());
        verify(refreshTokens).end("the-refresh-token");
        assertThat(setCookie(r)).startsWith("ossature_refresh=;").contains("Max-Age=0").contains("Path=/api/auth");
    }

    @Test
    void logoutWithoutACookieIsHarmless() throws Exception {
        mvc.perform(post("/api/auth/logout").header("X-Ossature-Client", "web")).andExpect(status().isNoContent());
    }
}
