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
    /** The public local-development passwords for the schema owner, the backend role and the agent role. */
    static final java.util.Set<String> DEV_DB_PASSWORDS = java.util.Set.of("ossature", "ossature_app", "ossature_agent");

    /** The database accounts as configured: who migrates, who the app and agent connect as. */
    public record DbSettings(String appUser, String appPassword, String migrationUser, String migrationPassword,
                             String agentPassword) {}
    static final int MIN_JWT_SECRET_BYTES = 32;

    private static boolean isDefaultOrBlank(String password) {
        return password == null || password.isBlank() || DEV_DB_PASSWORDS.contains(password);
    }

    public ProductionSafetyCheck(AppProperties props,
                                 @Value("${spring.datasource.username:}") String appUser,
                                 @Value("${spring.datasource.password:}") String appPassword,
                                 @Value("${spring.flyway.user:}") String migrationUser,
                                 @Value("${spring.flyway.password:}") String migrationPassword,
                                 @Value("${spring.flyway.placeholders.agentDbPassword:}") String agentPassword) {
        List<String> problems = problems(props, new DbSettings(appUser, appPassword, migrationUser, migrationPassword, agentPassword));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile - insecure configuration:\n  - "
                            + String.join("\n  - ", problems));
        }
    }

    static List<String> problems(AppProperties props, DbSettings db) {
        List<String> problems = new ArrayList<>();

        String jwt = props.auth() == null ? null : props.auth().jwtSecret();
        if (jwt == null || jwt.isBlank() || jwt.startsWith(DEV_JWT_SECRET_PREFIX)) {
            problems.add("JWT_SECRET is unset or still the public development default");
        } else if (jwt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            problems.add("JWT_SECRET is shorter than " + MIN_JWT_SECRET_BYTES + " bytes");
        }

        if (isDefaultOrBlank(db.appPassword())) {
            problems.add("APP_DB_PASSWORD is unset or still a development default");
        }
        if (isDefaultOrBlank(db.migrationPassword())) {
            problems.add("POSTGRES_PASSWORD (the schema owner used for migrations) is unset or still the development default");
        }
        if (isDefaultOrBlank(db.agentPassword())) {
            problems.add("AGENT_DB_PASSWORD is unset or still a development default");
        }
        if (db.appUser() != null && !db.appUser().isBlank() && db.appUser().equals(db.migrationUser())) {
            problems.add("the application connects as the migration/owner database account, which bypasses "
                    + "row-level security and least-privilege grants - use the restricted ossature_app account");
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
