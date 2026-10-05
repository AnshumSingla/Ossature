package com.ossature.interviewer.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed view of the {@code ossature.*} configuration tree in application.yml.
 */
@ConfigurationProperties(prefix = "ossature")
public record AppProperties(Agent agent, Session session, Cors cors, Auth auth, RateLimit rateLimit) {

    public record Agent(String baseUrl, Duration connectTimeout, Duration readTimeout, String sharedSecret) {}

    public record Session(Duration snapshotInterval, Duration summaryInterval) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Auth(String jwtSecret, Duration jwtExpiry, List<String> ownerEmails) {}

    public record RateLimit(
            int loginMaxPerEmail, Duration loginWindow,
            int loginMaxPerIp, Duration loginIpWindow,
            int sessionCreateMax, Duration sessionCreateWindow) {}
}
