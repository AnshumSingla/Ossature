package com.ossature.interviewer.ws;

import com.ossature.interviewer.logging.LogSafe;
import tools.jackson.databind.ObjectMapper;
import com.ossature.interviewer.ws.protocol.InterviewMessage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
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

    /**
     * Makes {@code socket} the session's connection. A reconnect replaces the old one, which is closed:
     * leaving it open would leave two live sockets for one session (PRD 6.2: one connection per session).
     */
    public void register(String sessionId, WebSocketSession socket) {
        WebSocketSession previous = connections.put(sessionId, socket);
        if (previous != null && previous != socket && previous.isOpen()) {
            try {
                previous.close(CloseStatus.POLICY_VIOLATION.withReason("replaced by a newer connection"));
            } catch (IOException e) {
                log.debug("Closing the replaced socket for session {} failed: {}", sessionId, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Forgets {@code socket}, but only if it is still the session's current connection. The old socket's
     * close notification arrives after a reconnect has already registered the new one, and must not
     * unregister the new one.
     */
    public void unregister(String sessionId, WebSocketSession socket) {
        connections.remove(sessionId, socket);
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
            LogSafe.warn(log, "Failed to push {} to session {}", e, message.type(), sessionId);
        }
    }
}
