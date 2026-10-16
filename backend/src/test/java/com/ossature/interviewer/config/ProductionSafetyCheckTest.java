package com.ossature.interviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** H3: the 'prod' profile must refuse to start on any public development default. */
class ProductionSafetyCheckTest {

    private static final String GOOD_JWT = "a-long-random-production-secret-0123456789abcdef";

    private static AppProperties props(String jwt, String agentSecret, List<String> origins) {
        return new AppProperties(
                new AppProperties.Agent("http://agent", Duration.ofSeconds(1), Duration.ofSeconds(1), agentSecret),
                null,
                new AppProperties.Cors(origins),
                new AppProperties.Auth(jwt, Duration.ofHours(1), List.of()),
                null, null);
    }

    private static List<String> problems(AppProperties p, String dbPassword) {
        return ProductionSafetyCheck.problems(p, dbPassword);
    }

    @Test
    void acceptsAFullyConfiguredDeployment() {
        var p = props(GOOD_JWT, "agent-secret", List.of("https://app.example.com"));
        assertThat(problems(p, "s3cure-db-pass")).isEmpty();
    }

    @Test
    void rejectsTheDevelopmentDefaults() {
        var p = props("dev-only-insecure-jwt-secret-change-me-before-deploying-anywhere", "",
                List.of("http://localhost:5173"));
        assertThat(problems(p, "ossature")).hasSize(4)
                .anyMatch(m -> m.contains("JWT_SECRET"))
                .anyMatch(m -> m.contains("POSTGRES_PASSWORD"))
                .anyMatch(m -> m.contains("AGENT_SHARED_SECRET"))
                .anyMatch(m -> m.contains("CORS"));
    }

    @Test
    void rejectsAShortJwtSecret() {
        var p = props("too-short", "agent-secret", List.of("https://app.example.com"));
        assertThat(problems(p, "s3cure-db-pass")).singleElement().asString().contains("shorter than");
    }

    @Test
    void rejectsPlainHttpAndEmptyOrigins() {
        assertThat(problems(props(GOOD_JWT, "x", List.of("http://app.example.com")), "pw-pw-pw")).hasSize(1);
        assertThat(problems(props(GOOD_JWT, "x", List.of()), "pw-pw-pw")).hasSize(1);
    }

    @Test
    void constructorThrowsWithAllProblemsListed() {
        var p = props("", "", List.of());
        assertThatThrownBy(() -> new ProductionSafetyCheck(p, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("AGENT_SHARED_SECRET");
    }
}
