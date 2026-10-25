package com.ossature.interviewer.service;

import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.domain.User;
import com.ossature.interviewer.repo.InterimSummaryRepository;
import com.ossature.interviewer.repo.InterviewSessionRepository;
import com.ossature.interviewer.repo.QuestionRepository;
import com.ossature.interviewer.repo.RefreshTokenRepository;
import com.ossature.interviewer.repo.SessionEventRepository;
import com.ossature.interviewer.repo.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * What a person can do with their own data (H21): download all of it, and delete all of it.
 *
 * <p>Both run as that person (row-level security shows them only their own rows); deletion additionally runs
 * in the {@code auth} scope, which is what lets the account row itself go - and only after the password has
 * been re-checked. Everything happens in one transaction: it either all goes or none of it does.
 */
@Service
public class AccountService {

    private final UserRepository users;
    private final InterviewSessionRepository sessions;
    private final SessionEventRepository events;
    private final InterimSummaryRepository summaries;
    private final QuestionRepository questions;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;

    public AccountService(UserRepository users, InterviewSessionRepository sessions, SessionEventRepository events,
                          InterimSummaryRepository summaries, QuestionRepository questions,
                          RefreshTokenRepository refreshTokens, PasswordEncoder encoder, ObjectMapper mapper) {
        this.users = users;
        this.sessions = sessions;
        this.events = events;
        this.summaries = summaries;
        this.questions = questions;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.mapper = mapper;
    }

    /** Everything stored about {@code userId}, as plain data (portability). */
    @Transactional(readOnly = true)
    public Map<String, Object> export(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("Unknown account"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now().toString());
        out.put("profile", Map.of("id", user.getId().toString(), "email", user.getEmail(),
                "displayName", user.getDisplayName(), "createdAt", user.getCreatedAt().toString()));

        List<Map<String, Object>> submitted = new ArrayList<>();
        questions.findBySubmittedByOrderByCreatedAtAsc(userId).forEach(q -> submitted.add(Map.of(
                "title", q.getTitle(), "prompt", q.getPrompt(), "status", q.getStatus().name(),
                "createdAt", q.getCreatedAt() == null ? "" : q.getCreatedAt().toString())));
        out.put("submittedQuestions", submitted);

        List<Map<String, Object>> sessionList = new ArrayList<>();
        for (InterviewSession s : sessions.findByUserIdOrderByStartedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId().toString());
            m.put("questionId", s.getQuestionId() == null ? null : s.getQuestionId().toString());
            m.put("state", s.getState().name());
            m.put("startedAt", String.valueOf(s.getStartedAt()));
            m.put("submittedAt", s.getSubmittedAt() == null ? null : s.getSubmittedAt().toString());
            m.put("endedAt", s.getEndedAt() == null ? null : s.getEndedAt().toString());
            m.put("finalDiagram", json(s.getFinalDiagram()));
            m.put("debrief", json(s.getDebrief()));
            m.put("summaries", summaries.findBySessionIdOrderByOrdinalAsc(s.getId()).stream()
                    .map(x -> Map.of("ordinal", x.getOrdinal(), "content", x.getContent())).toList());
            m.put("events", events.findBySessionIdOrderBySeqAsc(s.getId()).stream().map(e -> Map.of(
                    "seq", e.getSeq(), "type", e.getType(), "ts", e.getTs().toString(), "payload", json(e.getPayload()))).toList());
            sessionList.add(m);
        }
        out.put("sessions", sessionList);
        return out;
    }

    /**
     * Deletes the account and everything tied to it. Returns the ids of the deleted sessions so the caller can
     * also drop the interviewer service's working copies.
     *
     * @throws WrongPasswordException if {@code password} is not the account's
     */
    @Transactional
    public List<UUID> deleteAccount(UUID userId, String password) {
        User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("Unknown account"));
        if (user.getPasswordHash() == null || !encoder.matches(password, user.getPasswordHash())) {
            throw new WrongPasswordException();
        }
        List<UUID> sessionIds = sessions.findByUserIdOrderByStartedAtDesc(userId).stream().map(InterviewSession::getId).toList();
        sessions.deleteAllOwnedBy(userId);            // events and summaries go with them (ON DELETE CASCADE)
        questions.deleteUnpublishedBy(userId);        // pending / rejected prompts they wrote
        questions.anonymiseSubmissionsBy(userId);     // approved ones stay in the bank, unlinked from them
        refreshTokens.deleteAllFor(userId);           // every device is signed out
        if (users.deleteAccount(userId) != 1) {
            // Not running in the auth scope (or the row is gone): roll the whole thing back rather than
            // leave a half-deleted account behind.
            throw new IllegalStateException("Account deletion did not remove the account row");
        }
        return sessionIds;
    }

    private Object json(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
