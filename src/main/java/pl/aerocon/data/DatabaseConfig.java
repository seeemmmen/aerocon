package pl.aerocon.data;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Credentials live outside the JAR and can be overridden by environment variables. */
public record DatabaseConfig(String url, String user, String password) {
    public DatabaseConfig {
        if (url == null || !url.startsWith("jdbc:mariadb://") || user == null || user.isBlank() || password == null) {
            throw new DataAccessException("Set db.url, db.user and db.password in config/database.properties.");
        }
    }

    public static DatabaseConfig load() {
        Path path = Path.of(System.getProperty("aerocon.db.config", "config/database.properties"));
        Properties values = read(path, false);
        return new DatabaseConfig(value(values, "db.url", "AEROCON_DB_URL"),
            value(values, "db.user", "AEROCON_DB_USER"), value(values, "db.password", "AEROCON_DB_PASSWORD"));
    }

    /** Explicit configuration, useful for isolated integration tests. */
    public static DatabaseConfig load(Path path) {
        Properties values = read(path, true);
        return new DatabaseConfig(values.getProperty("db.url"), values.getProperty("db.user"), values.getProperty("db.password"));
    }

    private static Properties read(Path path, boolean required) {
        Properties values = new Properties();
        if (!Files.exists(path) && !required) return values;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { values.load(reader); }
        catch (IOException ex) { throw new DataAccessException("Cannot read the database configuration file.", ex); }
        return values;
    }

    private static String value(Properties values, String key, String variable) {
        String environment = System.getenv(variable);
        return environment == null ? values.getProperty(key) : environment;
    }

    @Override public String toString() { return "DatabaseConfig[credentials redacted]"; }
}
