package com.ossature.interviewer.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossature.interviewer.service.InterviewFlowService;
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
 * Single interview endpoint ({@code /ws/interview?sessionId=...&lastSeq=...}).
 * Persists every inbound frame to the replay log and hands domain-significant frames
 * (transcript.final, diagram.locked, session.end) to {@link InterviewFlowService}.
 */
@Component
public class InterviewWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final SessionSocketRegistry registry;
    private final InterviewFlowService flow;

    public InterviewWebSocketHandler(ObjectMapper mapper, SessionSocketRegistry registry,
                                     InterviewFlowService flow) {
        this.mapper = mapper;
        this.registry = registry;
        this.flow = flow;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        Map<String, String> params = queryParams(socket.getUri());
        String sessionId = params.get("sessionId");
        if (sessionId == null) {
            socket.close(CloseStatus.BAD_DATA.withReason("sessionId query param required"));
            return;
        }
        socket.getAttributes().put("sessionId", sessionId);
        registry.register(sessionId, socket);

        long lastSeq = parseLong(params.get("lastSeq"), 0L);
        flow.replaySince(UUID.fromString(sessionId), lastSeq, socket);
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
