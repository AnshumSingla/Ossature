package com.ossature.interviewer.config;

import com.ossature.interviewer.ws.InterviewWebSocketHandler;
import java.util.List;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
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

    /**
     * Caps a single inbound frame; Tomcat closes the connection (1009) if a client exceeds it.
     * Set through Tomcat's own context parameters rather than a ServletServerContainerFactoryBean,
     * because that bean needs a live WebSocket container and breaks any mock-servlet test context.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> webSocketFrameLimit() {
        int max = props.limits() == null ? 262_144 : props.limits().wsMaxMessageBytes();
        return factory -> factory.addContextCustomizers(context -> {
            context.addParameter("org.apache.tomcat.websocket.textBufferSize", String.valueOf(max));
            context.addParameter("org.apache.tomcat.websocket.binaryBufferSize", String.valueOf(max));
        });
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> origins = props.cors().allowedOrigins();
        registry.addHandler(handler, "/ws/interview")
                .setAllowedOrigins(origins.toArray(String[]::new));
    }
}
