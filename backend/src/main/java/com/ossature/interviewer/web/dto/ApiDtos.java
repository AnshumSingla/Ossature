package com.ossature.interviewer.web.dto;

import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.domain.Question;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** REST request/response shapes for the session lifecycle API (PRD 6.2). */
public final class ApiDtos {

    private ApiDtos() {}

    public record CreateSessionRequest(@NotNull UUID questionId) {}

    public record SessionView(UUID id, UUID questionId, String state, boolean diagramLocked,
                              Instant startedAt, Instant submittedAt, Instant endedAt) {
        public static SessionView of(InterviewSession s) {
            return new SessionView(s.getId(), s.getQuestionId(), s.getState().name(),
                    s.isDiagramLocked(), s.getStartedAt(), s.getSubmittedAt(), s.getEndedAt());
        }
    }

    public record QuestionView(UUID id, String slug, String title, String prompt,
                               String source, String status, boolean hasVettedReference) {
        public static QuestionView of(Question q) {
            return new QuestionView(q.getId(), q.getSlug(), q.getTitle(), q.getPrompt(),
                    q.getSource().name(), q.getStatus().name(), q.hasVettedReference());
        }
    }

    // The prompt is fed to an LLM for every session that uses the question, so its size is a
    // cost and a prompt-injection-surface limit, not just a storage one.
    /** One-time ticket for the interview WebSocket (see WsTicketService). */
    public record WsTicketView(String ticket, long expiresInSeconds) {}

    public record SubmitQuestionRequest(@NotBlank @Size(max = 140) @SafeText String title,
                                        @NotBlank @Size(max = 4000) @SafeText String prompt) {}
}
