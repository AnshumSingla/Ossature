package com.ossature.interviewer.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossature.interviewer.ws.protocol.InterviewMessage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks the live WebSocket connection per interview session and provides server-initiated
 * push (ai.thinking, AI chat turns, debrief.ready). One connection per session (PRD 6.2).
 */
@Component
public class SessionSocketRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionSocketRegistry.class);

    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public SessionSocketRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void register(String sessionId, WebSocketSession socket) {
        connections.put(sessionId, socket);
    }

    public void unregister(String sessionId) {
        connections.remove(sessionId);
    }

    /** Push a message to the candidate for {@code sessionId}. No-op if not connected. */
    public void push(String sessionId, InterviewMessage message) {
        WebSocketSession socket = connections.get(sessionId);
        if (socket == null || !socket.isOpen()) {
            log.debug("No open socket for session {}, dropping {}", sessionId, message.type());
            return;
        }
        try {
            socket.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.warn("Failed to push {} to session {}", message.type(), sessionId, e);
        }
    }
}
