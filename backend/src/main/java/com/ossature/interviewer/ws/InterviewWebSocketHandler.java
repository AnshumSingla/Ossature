package com.ossature.interviewer.ws;

import tools.jackson.databind.ObjectMapper;
import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.db.DbContext;
import com.ossature.interviewer.service.TextSafety;
import com.ossature.interviewer.security.WsTicketService;
import com.ossature.interviewer.service.InterviewFlowService;
import com.ossature.interviewer.service.RateLimiter;
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
 * Single interview endpoint ({@code /ws/interview?sessionId=...&lastSeq=...&ticket=...}).
 * Persists every inbound frame to the replay log and hands domain-significant frames
 * (transcript.final, diagram.locked, session.end) to {@link InterviewFlowService}.
 *
 * Auth happens here, not via the Spring Security filter chain: browsers can't set a custom
 * header on a WebSocket handshake, so a short-lived single-use {@code ticket} (see
 * {@link WsTicketService}) travels as a query param instead and is checked by hand against both
 * validity and session ownership (PRD 6.2 follow-up, H9).
 */
@Component
public class InterviewWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final SessionSocketRegistry registry;
    private final InterviewFlowService flow;
    private final WsTicketService tickets;
    private final SessionService sessions;
    private final RateLimiter rateLimiter;
    private final AppProperties.Limits limits;

    public InterviewWebSocketHandler(ObjectMapper mapper, SessionSocketRegistry registry,
                                     InterviewFlowService flow, WsTicketService tickets, SessionService sessions,
                                     RateLimiter rateLimiter, AppProperties props) {
        this.rateLimiter = rateLimiter;
        this.limits = props.limits();
        this.mapper = mapper;
        this.registry = registry;
        this.flow = flow;
        this.tickets = tickets;
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

        // The login token is never accepted here (it would end up in URLs and logs); only a
        // one-time ticket for exactly this session, obtained via POST /api/sessions/{id}/ws-ticket.
        UUID callerId = tickets.redeem(params.get("ticket"), sessionId).orElse(null);
        if (callerId == null) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("missing, invalid or already used ticket"));
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
            // Acting as the ticket holder: row-level security hides other users' sessions, so someone
            // else's session is simply "unknown" here.
            ownerId = DbContext.callAs(callerId, false, () -> sessions.get(sessionUuid).getUserId());
        } catch (IllegalArgumentException e) {
            socket.close(CloseStatus.BAD_DATA.withReason("unknown session"));
            return;
        }
        if (!callerId.equals(ownerId)) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("not your session"));
            return;
        }

        socket.getAttributes().put("sessionId", sessionId);
        socket.getAttributes().put("userId", callerId);
        registry.register(sessionId, socket);

        long lastSeq = parseLong(params.get("lastSeq"), 0L);
        DbContext.runAs(callerId, false, () -> flow.replaySince(sessionUuid, lastSeq, socket));
        log.info("WS connected for session {} (replay after seq {})", sessionId, lastSeq);
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage frame) throws Exception {
        String sessionId = (String) socket.getAttributes().get("sessionId");
        InterviewMessage message;
        try {
            message = mapper.readValue(frame.getPayload(), InterviewMessage.class);
        } catch (Exception e) {
            // Only the error type: Jackson's message quotes the text it could not parse (the transcript).
            log.warn("Dropping unparseable frame on session {}: {}", sessionId, e.getClass().getSimpleName());
            return;
        }
        if (message == null) { // the frame was the JSON literal null
            log.warn("Dropping empty frame on session {}", sessionId);
            return;
        }
        message = sanitized(message);
        if (!withinTranscriptLimits(sessionId, message)) {
            return;
        }
        UUID userId = (UUID) socket.getAttributes().get("userId");
        final InterviewMessage accepted = message;
        try {
            DbContext.runAs(userId, false, () -> flow.onInbound(UUID.fromString(sessionId), accepted));
        } catch (RuntimeException e) {
            // One bad frame (a missing field, a database refusal) must not drop the candidate's whole interview.
            // Type only: the message of a parse or SQL error can quote what they said.
            log.warn("Frame {} on session {} failed: {}", accepted.type(), sessionId, e.getClass().getSimpleName());
        }
    }

    /** User text from the socket with control characters and bidirectional overrides removed. */
    private static InterviewMessage sanitized(InterviewMessage m) {
        return switch (m) {
            case InterviewMessage.TranscriptFinal f ->
                    new InterviewMessage.TranscriptFinal(f.type(), f.sessionId(), TextSafety.clean(f.text()), f.ts());
            case InterviewMessage.TranscriptPartial p ->
                    new InterviewMessage.TranscriptPartial(p.type(), p.sessionId(), TextSafety.clean(p.text()), p.ts());
            case InterviewMessage.ChatMessage c ->
                    new InterviewMessage.ChatMessage(c.type(), c.sessionId(), c.sender(), TextSafety.clean(c.text()), c.kind(), c.ts());
            default -> m;
        };
    }

    /**
     * Every transcript.final becomes a paid LLM call, so cap how long one can be and how often
     * they arrive per session. Over-limit frames are dropped (and logged), not forwarded.
     */
    private boolean withinTranscriptLimits(String sessionId, InterviewMessage message) {
        String text;
        if (message instanceof InterviewMessage.TranscriptFinal f) {
            text = f.text();
        } else if (message instanceof InterviewMessage.TranscriptPartial p) {
            text = p.text();
        } else {
            return true; // not a transcript frame
        }
        if (text == null || text.length() > limits.transcriptMaxChars()) {
            log.warn("Dropping empty/oversized transcript frame on session {}", sessionId);
            return false;
        }
        if (message instanceof InterviewMessage.TranscriptFinal
                && !rateLimiter.allow("ws:transcript:" + sessionId, limits.transcriptMaxPerMinute(), 60_000)) {
            log.warn("Dropping transcript.final over rate limit on session {}", sessionId);
            return false;
        }
        return true;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        String sessionId = (String) socket.getAttributes().get("sessionId");
        if (sessionId != null) {
            registry.unregister(sessionId, socket);
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
