package com.ossature.interviewer.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/**
 * A throwaway Postgres database for tests that need the real thing (row-level security cannot be
 * tested against a mock): created with a random name, migrated with the application's own Flyway
 * migrations, and dropped on close. It never touches the development database.
 *
 * <p>Connects the way the application does: as the schema owner (default {@code ossature}, override
 * with POSTGRES_USER / POSTGRES_PASSWORD) to create the database and migrate; tests then use
 * {@link #app()} and {@link #agent()} to act as the restricted roles.
 */
public final class ScratchDatabase implements AutoCloseable {

    private static final String HOST = env("DB_HOST", "localhost");
    private static final String PORT = env("DB_PORT", "5432");
    private static final String OWNER = env("POSTGRES_USER", "ossature");
    private static final String OWNER_PASSWORD = env("POSTGRES_PASSWORD", "ossature");

    public static final String APP_USER = "ossature_app";
    public static final String APP_PASSWORD = "ossature_app";
    public static final String AGENT_USER = "ossature_agent";
    public static final String AGENT_PASSWORD = "ossature_agent";

    private final String name;

    private ScratchDatabase(String name) {
        this.name = name;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String url(String db) {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + db;
    }

    /** True if a Postgres we can create databases on is reachable; tests skip themselves otherwise. */
    public static boolean available() {
        try (Connection c = DriverManager.getConnection(url("postgres"), OWNER, OWNER_PASSWORD)) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Creates and migrates a fresh database. */
    public static ScratchDatabase create() {
        String name = "ossature_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url("postgres"), OWNER, OWNER_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create scratch database", e);
        }
        ScratchDatabase db = new ScratchDatabase(name);
        Flyway.configure()
                .dataSource(db.url(), OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", APP_PASSWORD, "agentDbPassword", AGENT_PASSWORD))
                .load()
                .migrate();
        return db;
    }

    public String name() {
        return name;
    }

    public String url() {
        return url(name);
    }

    public String ownerUser() {
        return OWNER;
    }

    public String ownerPassword() {
        return OWNER_PASSWORD;
    }

    /** The schema owner: bypasses row-level security, used here only to seed and inspect data. */
    public Connection owner() throws SQLException {
        return DriverManager.getConnection(url(), OWNER, OWNER_PASSWORD);
    }

    /** Connected as the backend's restricted role - what the application really uses. */
    public Connection app() throws SQLException {
        return DriverManager.getConnection(url(), APP_USER, APP_PASSWORD);
    }

    public Connection agent() throws SQLException {
        return DriverManager.getConnection(url(), AGENT_USER, AGENT_PASSWORD);
    }

    @Override
    public void close() {
        try (Connection c = DriverManager.getConnection(url("postgres"), OWNER, OWNER_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException e) {
            // best effort: a leftover scratch database is harmless
        }
    }
}
