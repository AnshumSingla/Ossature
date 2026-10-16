package com.ossature.interviewer.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.domain.Question;
import com.ossature.interviewer.security.JwtAuthFilter;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.security.SecurityConfig;
import com.ossature.interviewer.service.QuestionBankService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Role x endpoint checks for the fixes in H5 (unreviewed questions), H6 (actuator), H7 (input
 * size) - run through the real security filter chain with real, signed tokens, not mock users,
 * so a regression in matcher order or the JWT filter shows up here too.
 */
@WebMvcTest(controllers = QuestionController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, OwnerPolicy.class,
        RequestSizeLimitFilter.class, SecurityRulesWebTest.Props.class})
@TestPropertySource(properties = {
        "ossature.auth.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "ossature.auth.jwt-expiry=1h",
        "ossature.auth.owner-emails=owner@x.com",
        "ossature.limits.max-request-bytes=2048",
        "ossature.limits.ws-max-message-bytes=262144",
        "ossature.limits.transcript-max-chars=4000",
        "ossature.limits.transcript-max-per-minute=30",
})
class SecurityRulesWebTest {

    @org.springframework.boot.test.context.TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class Props {}

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean QuestionBankService bank;

    private final UUID submitter = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID questionId = UUID.randomUUID();

    private String token(UUID user, String email) {
        return "Bearer " + jwt.issue(user, email);
    }

    private Question pendingQuestion() {
        Question q = new Question("s", "Title", "Prompt", submitter);
        when(bank.get(questionId)).thenReturn(q);
        return q;
    }

    // ---- H5: reading a question by id ----

    @Test
    void approvedQuestionIsPublic() throws Exception {
        Question q = pendingQuestion();
        q.approve();
        mvc.perform(get("/api/questions/" + questionId)).andExpect(status().isOk());
    }

    @Test
    void pendingQuestionIsHiddenFromAnonymousAndOtherUsers() throws Exception {
        pendingQuestion();
        mvc.perform(get("/api/questions/" + questionId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/questions/" + questionId).header("Authorization", token(stranger, "b@x.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void pendingQuestionIsVisibleToItsSubmitterAndToTheOwner() throws Exception {
        pendingQuestion();
        mvc.perform(get("/api/questions/" + questionId).header("Authorization", token(submitter, "a@x.com")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/questions/" + questionId).header("Authorization", token(stranger, "owner@x.com")))
                .andExpect(status().isOk());
    }

    @Test
    void ownerRoleSurvivesEmailCaseDifferences() throws Exception {
        pendingQuestion();
        mvc.perform(get("/api/questions/" + questionId).header("Authorization", token(stranger, "OWNER@X.com")))
                .andExpect(status().isOk());
    }

    // ---- existing owner-only routes still hold (matcher-order regression guard) ----

    @Test
    void pendingListIsOwnerOnly() throws Exception {
        when(bank.listPending()).thenReturn(List.of());
        mvc.perform(get("/api/questions/pending")).andExpect(status().isForbidden());
        mvc.perform(get("/api/questions/pending").header("Authorization", token(stranger, "b@x.com")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/questions/pending").header("Authorization", token(stranger, "owner@x.com")))
                .andExpect(status().isOk());
    }

    @Test
    void approveIsOwnerOnly() throws Exception {
        mvc.perform(post("/api/questions/" + questionId + "/approve")
                        .header("Authorization", token(stranger, "b@x.com")))
                .andExpect(status().isForbidden());
    }

    // ---- H6: actuator ----

    @Test
    void actuatorBeyondHealthIsOwnerOnly() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header("Authorization", token(stranger, "b@x.com")))
                .andExpect(status().isForbidden());
        // The owner gets past security; this slice has no actuator endpoints, so 404 rather than 403.
        mvc.perform(get("/actuator/metrics").header("Authorization", token(stranger, "owner@x.com")))
                .andExpect(status().isNotFound());
    }

    // ---- H7: input limits ----

    @Test
    void oversizedBodyIsRejectedBeforeItIsParsed() throws Exception {
        String huge = "{\"title\":\"t\",\"prompt\":\"" + "x".repeat(5000) + "\"}";
        mvc.perform(post("/api/questions").header("Authorization", token(stranger, "b@x.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().is(413));
    }

    @Test
    void overlongTitleIsRejectedWithAReadableMessage() throws Exception {
        String body = "{\"title\":\"" + "t".repeat(141) + "\",\"prompt\":\"fine\"}";
        mvc.perform(post("/api/questions").header("Authorization", token(stranger, "b@x.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Title")));
    }

    @Test
    void aNormalSubmissionStillWorks() throws Exception {
        when(bank.submitForReview(any(), any(), any())).thenReturn(new Question("s", "T", "P", stranger));
        mvc.perform(post("/api/questions").header("Authorization", token(stranger, "b@x.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Design a thing\",\"prompt\":\"Design it well.\"}"))
                .andExpect(status().isAccepted());
    }
}
