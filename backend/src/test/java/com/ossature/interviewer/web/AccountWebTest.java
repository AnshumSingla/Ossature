package com.ossature.interviewer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.config.RefreshProperties;
import com.ossature.interviewer.security.JwtAuthFilter;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.security.SecurityConfig;
import com.ossature.interviewer.service.AccountService;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.SessionService;
import com.ossature.interviewer.service.WrongPasswordException;
import java.util.List;
import java.util.Map;
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

/** H21: downloading and deleting your own data over HTTP. */
@WebMvcTest(controllers = AccountController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, OwnerPolicy.class, RateLimiter.class,
        RefreshCookies.class, RequestSizeLimitFilter.class, AccountWebTest.Props.class})
@TestPropertySource(properties = {
        "ossature.auth.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "ossature.auth.jwt-expiry=15m",
        "ossature.auth.owner-emails=owner@x.com",
        "ossature.refresh.cookie-secure=true",
        "ossature.limits.max-request-bytes=65536",
        "ossature.limits.ws-max-message-bytes=262144",
        "ossature.limits.transcript-max-chars=4000",
        "ossature.limits.transcript-max-per-minute=30",
})
class AccountWebTest {

    @TestConfiguration
    @EnableConfigurationProperties({AppProperties.class, RefreshProperties.class})
    static class Props {}

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean AccountService accounts;
    @MockitoBean SessionService sessions;

    private String bearer(UUID user) {
        return "Bearer " + jwt.issue(user, user + "@x.com");
    }

    @Test
    void anonymousCallersCanNeitherExportNorDelete() throws Exception {
        mvc.perform(get("/api/account/export")).andExpect(status().isForbidden());
        mvc.perform(post("/api/account/delete").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
        verify(accounts, never()).deleteAccount(any(), any());
    }

    @Test
    void exportReturnsTheCallersOwnDataAsADownload() throws Exception {
        UUID user = UUID.randomUUID();
        doReturn(Map.of("profile", Map.of("email", "me@x.com"))).when(accounts).export(user);
        mvc.perform(get("/api/account/export").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.email").value("me@x.com"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment"));
    }

    @Test
    void deletingNeedsThePasswordAndAWrongOneDeletesNothing() throws Exception {
        UUID user = UUID.randomUUID();
        doThrow(new WrongPasswordException()).when(accounts).deleteAccount(eq(user), eq("nope"));
        mvc.perform(post("/api/account/delete").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"nope\"}"))
                .andExpect(status().isForbidden()) // 403, not 401: the client must not treat it as an expired session
                .andExpect(jsonPath("$.detail").value("That password isn't correct."));
        verify(sessions, never()).forgetAgentState(any());
        // and a body with no password is rejected before anything runs
        mvc.perform(post("/api/account/delete").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSuccessfulDeletionSignsEveryDeviceOutAndDropsTheAgentsCopies() throws Exception {
        UUID user = UUID.randomUUID();
        List<UUID> deleted = List.of(UUID.randomUUID(), UUID.randomUUID());
        doReturn(deleted).when(accounts).deleteAccount(eq(user), eq("right-Pass1"));
        mvc.perform(post("/api/account/delete").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"right-Pass1\"}"))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                        .startsWith("ossature_refresh=;").contains("Max-Age=0"));
        deleted.forEach(id -> verify(sessions).forgetAgentState(id));
    }
}
