package com.ossature.interviewer.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start under the {@code prod} profile with any of the local-development
 * defaults still in place. The defaults in application.yml exist so {@code mvn spring-boot:run}
 * works on a fresh checkout; they are public, so running them anywhere reachable is a breach
 * waiting to happen. Failing at startup beats a quiet insecure deployment.
 */
@Component
@Profile("prod")
public class ProductionSafetyCheck {

    static final String DEV_JWT_SECRET_PREFIX = "dev-only";
    static final String DEV_DB_PASSWORD = "ossature";
    static final int MIN_JWT_SECRET_BYTES = 32;

    public ProductionSafetyCheck(AppProperties props,
                                 @Value("${spring.datasource.password:}") String dbPassword) {
        List<String> problems = problems(props, dbPassword);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile - insecure configuration:\n  - "
                            + String.join("\n  - ", problems));
        }
    }

    static List<String> problems(AppProperties props, String dbPassword) {
        List<String> problems = new ArrayList<>();

        String jwt = props.auth() == null ? null : props.auth().jwtSecret();
        if (jwt == null || jwt.isBlank() || jwt.startsWith(DEV_JWT_SECRET_PREFIX)) {
            problems.add("JWT_SECRET is unset or still the public development default");
        } else if (jwt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            problems.add("JWT_SECRET is shorter than " + MIN_JWT_SECRET_BYTES + " bytes");
        }

        if (dbPassword == null || dbPassword.isBlank() || DEV_DB_PASSWORD.equals(dbPassword)) {
            problems.add("POSTGRES_PASSWORD is unset or still the development default");
        }

        String agentSecret = props.agent() == null ? null : props.agent().sharedSecret();
        if (agentSecret == null || agentSecret.isBlank()) {
            problems.add("AGENT_SHARED_SECRET is blank, so the agent would accept calls from anyone");
        }

        List<String> origins = props.cors() == null ? List.of() : props.cors().allowedOrigins();
        if (origins == null || origins.isEmpty()) {
            problems.add("ossature.cors.allowed-origins is empty");
        } else {
            for (String origin : origins) {
                if (origin.contains("localhost") || origin.contains("127.0.0.1") || !origin.startsWith("https://")) {
                    problems.add("CORS origin '" + origin + "' is not an https:// production origin");
                }
            }
        }
        return problems;
    }
}
