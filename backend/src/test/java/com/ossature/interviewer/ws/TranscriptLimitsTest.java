package com.ossature.interviewer.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.service.InterviewFlowService;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.SessionService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/** H7: every transcript.final costs an LLM call, so size and rate are capped per session. */
class TranscriptLimitsTest {

    private static final UUID SESSION = UUID.randomUUID();

    private final InterviewFlowService flow = mock(InterviewFlowService.class);
    private final WebSocketSession socket = mock(WebSocketSession.class);
    private InterviewWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        var limits = new AppProperties.Limits(65_536, 262_144, 100, 3);
        var props = new AppProperties(null, null, null, null, null, limits);
        handler = new InterviewWebSocketHandler(new ObjectMapper(), mock(SessionSocketRegistry.class), flow,
                mock(JwtService.class), mock(SessionService.class), new RateLimiter(), props);
        when(socket.getAttributes()).thenReturn(Map.of("sessionId", SESSION.toString()));
    }

    private static TextMessage frame(String type, String text) {
        return new TextMessage("{\"type\":\"" + type + "\",\"sessionId\":\"" + SESSION + "\",\"text\":\""
                + text + "\",\"ts\":1}");
    }

    @Test
    void normalTranscriptIsForwarded() throws Exception {
        handler.handleTextMessage(socket, frame("transcript.final", "we shard by user id"));
        verify(flow).onInbound(eq(SESSION), any());
    }

    @Test
    void oversizedTranscriptIsDroppedBeforeItReachesTheLlm() throws Exception {
        handler.handleTextMessage(socket, frame("transcript.final", "x".repeat(101)));
        handler.handleTextMessage(socket, frame("transcript.partial", "x".repeat(101)));
        verify(flow, never()).onInbound(any(), any());
    }

    @Test
    void floodingFinalTranscriptsIsCappedPerSession() throws Exception {
        for (int i = 0; i < 10; i++) {
            handler.handleTextMessage(socket, frame("transcript.final", "word " + i));
        }
        verify(flow, times(3)).onInbound(any(), any()); // limit is 3 per minute in this test
    }

    @Test
    void partialsAreNotRateLimitedAsTheyDoNotCostAnLlmCall() throws Exception {
        for (int i = 0; i < 10; i++) {
            handler.handleTextMessage(socket, frame("transcript.partial", "word " + i));
        }
        verify(flow, times(10)).onInbound(any(), any());
    }
}
