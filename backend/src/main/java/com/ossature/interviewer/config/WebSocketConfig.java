package com.ossature.interviewer.config;

import com.ossature.interviewer.ws.InterviewWebSocketHandler;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the single interview WebSocket endpoint. One connection per active session
 * carries transcript chunks, diagram snapshots, chat turns and lock/submit signals (PRD 6.4).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final InterviewWebSocketHandler handler;
    private final AppProperties props;

    public WebSocketConfig(InterviewWebSocketHandler handler, AppProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> origins = props.cors().allowedOrigins();
        registry.addHandler(handler, "/ws/interview")
                .setAllowedOrigins(origins.toArray(String[]::new));
    }
}
