package com.ossature.interviewer.service;

import com.ossature.interviewer.logging.LogSafe;
import tools.jackson.databind.ObjectMapper;
import com.ossature.interviewer.agent.AgentClient;
import com.ossature.interviewer.agent.AgentDtos;
import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.domain.Question;
import com.ossature.interviewer.domain.QuestionStatus;
import com.ossature.interviewer.repo.InterviewSessionRepository;
import com.ossature.interviewer.repo.QuestionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session lifecycle: create (and kick off the LangGraph graph), fetch, list, submit, end.
 * The Council node is skipped when the chosen question carries a vetted reference (PRD 6.3).
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final InterviewSessionRepository sessions;
    private final QuestionRepository questions;
    private final AgentClient agent;
    private final EventLogService eventLog;
    private final ObjectMapper mapper;

    public SessionService(InterviewSessionRepository sessions, QuestionRepository questions,
                          AgentClient agent, EventLogService eventLog, ObjectMapper mapper) {
        this.sessions = sessions;
        this.questions = questions;
        this.agent = agent;
        this.eventLog = eventLog;
        this.mapper = mapper;
    }

    @Transactional
    public InterviewSession create(UUID userId, UUID questionId) {
        Question question = questions.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown question " + questionId));
        // Only reviewed questions may be run. A pending/rejected community prompt goes
        // straight to the LLM, so letting anyone start one by id would bypass the owner review.
        // Same message as "not found" so the response doesn't reveal that the id exists.
        if (question.getStatus() != QuestionStatus.APPROVED) {
            throw new IllegalArgumentException("Unknown question " + questionId);
        }

        InterviewSession session = new InterviewSession(userId, questionId);
        if (question.hasVettedReference()) {
            session.attachReferenceSolution(question.getReferenceSolution());
        }
        session = sessions.save(session);

        var start = new AgentDtos.StartRequest(
                session.getId().toString(),
                question.getPrompt(),
                question.hasVettedReference(),
                readJson(question.getReferenceSolution()));
        try {
            var opening = agent.startSession(start);
            if (opening != null && opening.text() != null) {
                eventLog.append(session.getId(), "chat.message",
                        Map.of("sender", "ai", "text", opening.text(), "kind", opening.kind()));
            }
        } catch (RuntimeException e) {
            LogSafe.warn(log, "Agent start failed for session {}; continuing without opening turn", e, session.getId());
        }
        return session;
    }

    @Transactional(readOnly = true)
    public InterviewSession get(UUID id) {
        return sessions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session " + id));
    }

    @Transactional(readOnly = true)
    public List<InterviewSession> listForUser(UUID userId) {
        return sessions.findByUserIdOrderByStartedAtDesc(userId);
    }

    @Transactional
    public InterviewSession end(UUID id, String debriefJson) {
        InterviewSession session = get(id);
        session.end(debriefJson);
        return sessions.save(session);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
