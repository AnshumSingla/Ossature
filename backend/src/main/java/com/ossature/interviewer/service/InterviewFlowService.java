package com.ossature.interviewer.service;

import com.ossature.interviewer.logging.LogSafe;
import tools.jackson.databind.ObjectMapper;
import com.ossature.interviewer.agent.AgentClient;
import com.ossature.interviewer.agent.AgentDtos;
import com.ossature.interviewer.domain.InterimSummary;
import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.domain.SessionState;
import com.ossature.interviewer.repo.InterimSummaryRepository;
import com.ossature.interviewer.repo.InterviewSessionRepository;
import com.ossature.interviewer.ws.SessionSocketRegistry;
import com.ossature.interviewer.ws.protocol.InterviewMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Routes inbound WebSocket frames to persistence + the LangGraph bridge, and pushes AI
 * output back over the socket. Diagram evaluation is submit-triggered only (PRD 6.2 / FR5).
 */
@Service
public class InterviewFlowService {

    private static final Logger log = LoggerFactory.getLogger(InterviewFlowService.class);

    private final EventLogService eventLog;
    private final AgentClient agent;
    private final SessionSocketRegistry registry;
    private final InterviewSessionRepository sessions;
    private final InterimSummaryRepository interimSummaries;
    private final ObjectMapper mapper;

    public InterviewFlowService(EventLogService eventLog, AgentClient agent,
                                SessionSocketRegistry registry, InterviewSessionRepository sessions,
                                InterimSummaryRepository interimSummaries, ObjectMapper mapper) {
        this.eventLog = eventLog;
        this.agent = agent;
        this.registry = registry;
        this.sessions = sessions;
        this.interimSummaries = interimSummaries;
        this.mapper = mapper;
    }

    /**
     * Handles one frame from the candidate - but only if the session's state allows it. Every transcript turn,
     * review and debrief is a paid model call, so an ended session accepts nothing, the diagram can be locked
     * (and reviewed) once, and a locked diagram no longer changes.
     */
    public void onInbound(UUID sessionId, InterviewMessage message) {
        InterviewSession session = sessions.findById(sessionId).orElse(null);
        if (session == null || session.getState() == SessionState.ENDED) {
            log.debug("Ignoring {} for session {}: not active", message.type(), sessionId);
            return;
        }
        switch (message) {
            case InterviewMessage.TranscriptPartial p ->
                    eventLog.append(sessionId, p.type(), Map.of("text", p.text(), "ts", p.ts()));
            case InterviewMessage.TranscriptFinal f -> handleTranscriptFinal(sessionId, f);
            case InterviewMessage.DiagramSnapshot s -> {
                if (!session.isDiagramLocked()) {
                    eventLog.append(sessionId, s.type(), Map.of("nodes", s.nodes(), "edges", s.edges()));
                }
            }
            case InterviewMessage.ChatMessage c -> {
                // The interviewer's turns are written by the server; a client may only speak as the candidate.
                if ("candidate".equals(c.sender())) {
                    eventLog.append(sessionId, c.type(), Map.of("sender", "candidate", "text", c.text(), "kind", ""));
                } else {
                    log.warn("Dropping a client chat message claiming to be '{}' on session {}", c.sender(), sessionId);
                }
            }
            case InterviewMessage.DiagramLocked l -> {
                if (session.getState() == SessionState.ACTIVE && !session.isDiagramLocked()) {
                    handleSubmit(sessionId, l);
                } else {
                    log.debug("Ignoring a repeated diagram.locked for session {}", sessionId);
                }
            }
            case InterviewMessage.SessionEnd e -> handleSessionEnd(sessionId, e);
            case InterviewMessage.AiThinking ignored -> { /* server-originated only */ }
            case InterviewMessage.DebriefReady ignored -> { /* server-originated only */ }
        }
    }

    private void handleTranscriptFinal(UUID sessionId, InterviewMessage.TranscriptFinal f) {
        eventLog.append(sessionId, f.type(), Map.of("text", f.text(), "ts", f.ts()));
        try {
            var turn = agent.sendTranscript(sessionId.toString(),
                    new AgentDtos.TranscriptRequest(sessionId.toString(), f.text()));
            if (turn != null && turn.text() != null && !turn.text().isBlank()) {
                pushAiTurn(sessionId, turn.text(), turn.kind());
            }
        } catch (RuntimeException e) {
            LogSafe.warn(log, "Agent transcript call failed for session {}", e, sessionId);
        }
    }

    @Transactional
    public void handleSubmit(UUID sessionId, InterviewMessage.DiagramLocked locked) {
        InterviewSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session " + sessionId));
        String finalDiagramJson = writeJson(locked.finalDiagram());
        session.submit(finalDiagramJson);
        sessions.save(session);
        eventLog.append(sessionId, locked.type(), Map.of("finalDiagram", locked.finalDiagram()));

        registry.push(sessionId.toString(), new InterviewMessage.AiThinking(
                "ai.thinking", sessionId.toString(), "review", now()));
        try {
            var review = agent.submitDiagram(sessionId.toString(),
                    new AgentDtos.SubmitRequest(sessionId.toString(), readMap(finalDiagramJson)));
            if (review != null) {
                for (AgentDtos.Flaw flaw : review.flaws()) {
                    pushAiTurn(sessionId, flaw.questionPrompt(), "flaw");
                }
            }
        } catch (RuntimeException e) {
            LogSafe.warn(log, "Review node call failed for session {}", e, sessionId);
        }
    }

    @Transactional
    public void handleSessionEnd(UUID sessionId, InterviewMessage.SessionEnd end) {
        eventLog.append(sessionId, end.type(), Map.of("ts", end.ts()));
        InterviewSession session = sessions.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        List<String> summaryContents = interimSummaries.findBySessionIdOrderByOrdinalAsc(sessionId).stream()
                .map(InterimSummary::getContent)
                .toList();
        AgentDtos.DebriefResponse debrief = null;
        try {
            debrief = agent.requestDebrief(sessionId.toString(),
                    new AgentDtos.DebriefRequest(sessionId.toString(), summaryContents, readMap(session.getReferenceSolution())));
        } catch (RuntimeException e) {
            LogSafe.warn(log, "Debrief node call failed for session {}", e, sessionId);
        }
        // The candidate asked to end, so the interview ends - even if the model could not produce a debrief
        // (the session would otherwise be stuck open, and keep accepting paid frames, until it is purged).
        session.end(debrief == null ? null : writeJson(debrief));
        sessions.save(session);
        if (debrief != null) {
            registry.push(sessionId.toString(), new InterviewMessage.DebriefReady(
                    "debrief.ready", sessionId.toString(),
                    debrief.strengths(), debrief.gaps(), debrief.unresolved(),
                    debrief.referenceComparison(), now()));
        }
        // The backend now holds the record (with or without a debrief): the agent's working copy is no longer needed.
        try {
            agent.endSession(sessionId.toString());
        } catch (RuntimeException e) {
            LogSafe.warn(log, "Agent session cleanup failed for {} (harmless, will just linger)", e, sessionId);
        }
    }

    /** Full event log for a session, same shape as a WS replay frame (history/replay UI). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> fullHistory(UUID sessionId) {
        return eventLog.since(sessionId, 0).stream()
                .map(event -> Map.<String, Object>of(
                        "type", event.getType(),
                        "seq", event.getSeq(),
                        "sessionId", sessionId.toString(),
                        "payload", readMap(event.getPayload()),
                        "ts", event.getTs().toEpochMilli()))
                .toList();
    }

    public void replaySince(UUID sessionId, long lastSeq, WebSocketSession socket) {
        eventLog.since(sessionId, lastSeq).forEach(event -> {
            try {
                socket.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                        "type", event.getType(),
                        "seq", event.getSeq(),
                        "sessionId", sessionId.toString(),
                        "payload", mapper.readTree(event.getPayload()),
                        "ts", event.getTs().toEpochMilli()))));
            } catch (Exception e) {
                LogSafe.warn(log, "Replay failed for session {} seq {}", e, sessionId, event.getSeq());
            }
        });
    }

    private void pushAiTurn(UUID sessionId, String text, String kind) {
        eventLog.append(sessionId, "chat.message", Map.of("sender", "ai", "text", text, "kind", kind));
        registry.push(sessionId.toString(), new InterviewMessage.ChatMessage(
                "chat.message", sessionId.toString(), "ai", text, kind, now()));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
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
