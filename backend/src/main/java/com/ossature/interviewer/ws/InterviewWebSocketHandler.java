package com.ossature.interviewer.ws;

import tools.jackson.databind.ObjectMapper;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.service.InterviewFlowService;
import com.ossature.interviewer.service.SessionService;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import com.ossature.interviewer.ws.protocol.InterviewMessage;

/**
 * Single interview endpoint ({@code /ws/interview?sessionId=...&lastSeq=...&token=...}).
 * Persists every inbound frame to the replay log and hands domain-significant frames
 * (transcript.final, diagram.locked, session.end) to {@link InterviewFlowService}.
 *
 * Auth happens here, not via the Spring Security filter chain: browsers can't set a custom
 * header on a WebSocket handshake, so the JWT travels as a {@code token} query param instead
 * and is checked by hand against both validity and session ownership (PRD 6.2 follow-up).
 */
@Component
public class InterviewWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final SessionSocketRegistry registry;
    private final InterviewFlowService flow;
    private final JwtService jwt;
    private final SessionService sessions;

    public InterviewWebSocketHandler(ObjectMapper mapper, SessionSocketRegistry registry,
                                     InterviewFlowService flow, JwtService jwt, SessionService sessions) {
        this.mapper = mapper;
        this.registry = registry;
        this.flow = flow;
        this.jwt = jwt;
        this.sessions = sessions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        Map<String, String> params = queryParams(socket.getUri());
        String sessionId = params.get("sessionId");
        if (sessionId == null) {
            socket.close(CloseStatus.BAD_DATA.withReason("sessionId query param required"));
            return;
        }

        UUID callerId = jwt.verify(params.get("token")).orElse(null);
        if (callerId == null) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("missing or invalid token"));
            return;
        }
        UUID sessionUuid;
        try {
            sessionUuid = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            socket.close(CloseStatus.BAD_DATA.withReason("malformed sessionId"));
            return;
        }
        UUID ownerId;
        try {
            ownerId = sessions.get(sessionUuid).getUserId();
        } catch (IllegalArgumentException e) {
            socket.close(CloseStatus.BAD_DATA.withReason("unknown session"));
            return;
        }
        if (!callerId.equals(ownerId)) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("not your session"));
            return;
        }

        socket.getAttributes().put("sessionId", sessionId);
        registry.register(sessionId, socket);

        long lastSeq = parseLong(params.get("lastSeq"), 0L);
        flow.replaySince(sessionUuid, lastSeq, socket);
        log.info("WS connected for session {} (replay after seq {})", sessionId, lastSeq);
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage frame) throws Exception {
        String sessionId = (String) socket.getAttributes().get("sessionId");
        InterviewMessage message;
        try {
            message = mapper.readValue(frame.getPayload(), InterviewMessage.class);
        } catch (Exception e) {
            log.warn("Dropping unparseable frame on session {}: {}", sessionId, e.getMessage());
            return;
        }
        flow.onInbound(UUID.fromString(sessionId), message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        String sessionId = (String) socket.getAttributes().get("sessionId");
        if (sessionId != null) {
            registry.unregister(sessionId);
        }
        log.info("WS closed for session {} ({})", sessionId, status);
    }

    private static Map<String, String> queryParams(URI uri) {
        if (uri == null) {
            return Map.of();
        }
        var single = UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
        return single;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
