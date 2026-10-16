package com.ossature.interviewer.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed view of the {@code ossature.*} configuration tree in application.yml.
 */
@ConfigurationProperties(prefix = "ossature")
public record AppProperties(Agent agent, Session session, Cors cors, Auth auth, RateLimit rateLimit,
                            Limits limits) {

    public record Agent(String baseUrl, Duration connectTimeout, Duration readTimeout, String sharedSecret) {}

    public record Session(Duration snapshotInterval, Duration summaryInterval) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Auth(String jwtSecret, Duration jwtExpiry, List<String> ownerEmails) {}

    /**
     * Hard input ceilings. {@code maxRequestBytes} applies to every /api request body,
     * {@code wsMaxMessageBytes} to a single WebSocket frame, and the transcript values cap the
     * one frame type that triggers a paid LLM call each time.
     */
    public record Limits(int maxRequestBytes, int wsMaxMessageBytes, int transcriptMaxChars,
                         int transcriptMaxPerMinute) {}

    public record RateLimit(
            int loginMaxPerEmail, Duration loginWindow,
            int loginMaxPerIp, Duration loginIpWindow,
            int sessionCreateMax, Duration sessionCreateWindow) {}
}
