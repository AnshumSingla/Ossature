package com.ossature.interviewer.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossature.interviewer.ws.protocol.InterviewMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * A1.7: a client reconnecting (to this or another instance) must end up with working pushes. The old
 * socket's close notification arrives AFTER the new connection registered; it used to unregister the
 * new one, silently dropping every push for the rest of the interview.
 */
class SessionSocketRegistryTest {

    private final SessionSocketRegistry registry = new SessionSocketRegistry(JsonMapper.builder().build());
    private static final InterviewMessage MESSAGE = new InterviewMessage.AiThinking("ai.thinking", "s1", "review", 1L);

    private static WebSocketSession openSocket() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }

    @Test
    void aReconnectReplacesTheOldSocketAndClosesIt() throws Exception {
        WebSocketSession old = openSocket();
        WebSocketSession fresh = openSocket();
        registry.register("s1", old);
        registry.register("s1", fresh);

        verify(old).close(any(CloseStatus.class));
        registry.push("s1", MESSAGE);
        verify(fresh).sendMessage(any(TextMessage.class));
        verify(old, never()).sendMessage(any());
    }

    @Test
    void theOldSocketsCloseNotificationDoesNotUnregisterTheNewOne() throws Exception {
        WebSocketSession old = openSocket();
        WebSocketSession fresh = openSocket();
        registry.register("s1", old);
        registry.register("s1", fresh);

        registry.unregister("s1", old); // what afterConnectionClosed(old) does, after the reconnect

        registry.push("s1", MESSAGE);
        verify(fresh).sendMessage(any(TextMessage.class));
    }

    @Test
    void theCurrentSocketsCloseUnregistersIt() throws Exception {
        WebSocketSession socket = openSocket();
        registry.register("s1", socket);
        registry.unregister("s1", socket);

        registry.push("s1", MESSAGE);
        verify(socket, never()).sendMessage(any());
    }
}
