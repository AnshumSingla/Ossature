package com.ossature.interviewer.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed view of the {@code ossature.*} configuration tree in application.yml.
 */
@ConfigurationProperties(prefix = "ossature")
public record AppProperties(Agent agent, Session session, Cors cors) {

    public record Agent(String baseUrl, Duration connectTimeout, Duration readTimeout) {}

    public record Session(Duration snapshotInterval, Duration summaryInterval) {}

    public record Cors(List<String> allowedOrigins) {}
}
