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

    /**
     * Login: a small budget per (account, source IP) so one address can't hammer an account, a
     * much larger account-wide cap so guessing spread over many addresses is still bounded, and
     * a per-IP cap against spraying many accounts. The account-wide cap is deliberately high:
     * a tight one would let anyone lock a victim out just by sending wrong passwords for them.
     * Signup has its own per-IP budget and never touches anyone's login budget.
     */
    public record RateLimit(
            int loginMaxPerEmailAndIp, Duration loginEmailIpWindow,
            int loginMaxPerEmailGlobal, Duration loginEmailGlobalWindow,
            int loginMaxPerIp, Duration loginIpWindow,
            int signupMaxPerIp, Duration signupWindow,
            int sessionCreateMax, Duration sessionCreateWindow) {}
}
