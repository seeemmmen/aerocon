package pl.aerocon.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import pl.aerocon.model.Incident;
import pl.aerocon.model.Operator;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in tests against an actual MariaDB instance, restricted to the local aerocon_test schema.
 * Configure AEROCON_TEST_DB_URL / USER / PASSWORD (or equivalent aerocon.test.db.* properties),
 * or pass -Daerocon.test.db.config=path/to/test.properties with db.url/user/password keys.
 * No production configuration is read and no database/schema is dropped by this suite.
 */
@Execution(ExecutionMode.SAME_THREAD)
final class DatabaseIntegrationTest {
    private static final String TEST_PASSWORD = "Integration-Aerocon-42!";
    private static DatabaseConfig config;

    private JdbcAeroconRepository repository;
    private final List<Long> createdOperatorIds = new ArrayList<>();
    private final List<String> createdIncidentKeys = new ArrayList<>();

    @BeforeAll
    static void requireExplicitTestDatabase() throws Exception {
        Properties file = new Properties();
        String configPath = setting("aerocon.test.db.config", "AEROCON_TEST_DB_CONFIG");
        if (configPath != null && !configPath.isBlank()) {
            try (Reader reader = Files.newBufferedReader(Path.of(configPath), StandardCharsets.UTF_8)) {
                file.load(reader);
            }
        }
        String url = configured("url", "URL", file);
        String user = configured("user", "USER", file);
        String password = configured("password", "PASSWORD", file);
        assumeTrue(url != null && user != null && password != null,
                "MariaDB integration tests require explicit AEROCON_TEST_DB_* settings or aerocon.test.db.config.");
        assertTrue(url.matches("jdbc:mariadb://(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::3306)?/aerocon_test(?:\\?[^#]*)?"),
                "Integration tests only accept the local MariaDB aerocon_test schema on port 3306.");
        config = new DatabaseConfig(url, user, password);
        // Verify the server-selected schema before initialize() is allowed to create any tables.
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT DATABASE()");
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals("aerocon_test", result.getString(1), "Refusing to modify a non-test database.");
            assertTrue(connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mariadb"),
                    "These integration tests must run against MariaDB, not an in-memory substitute.");
        }
    }

    @BeforeEach
    void openRepository() {
        repository = new JdbcAeroconRepository(config);
        repository.initialize();
    }

    @AfterEach
    void cleanUpOnlyRowsCreatedByThisTest() throws Exception {
        if (repository == null) return;
        try {
            // There is no incident-deletion API: remove only this test's unique source keys.
            try (Connection connection = connect();
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM incidents WHERE source_key = ?")) {
                for (String sourceKey : createdIncidentKeys) {
                    delete.setString(1, sourceKey);
                    delete.executeUpdate();
                }
            }
            for (long id : createdOperatorIds) {
                if (repository.findOperators().stream().anyMatch(operator -> operator.id() == id)) {
                    repository.deleteOperator(id);
                }
            }
        } finally {
            repository.close();
        }
    }

    @Test
    void initializationIsOneTimeAndDoesNotResurrectADeletedSeedOperator() {
        List<Operator> before = repository.findOperators();
        assertFalse(before.isEmpty(), "The initial database must contain seeded operators.");
        Operator oliver = before.stream().filter(operator -> operator.username().equals("oliver.grant"))
                .findFirst().orElse(null);
        if (oliver != null) {
            assertEquals("Oliver Grant", oliver.name());
            assertFalse(oliver.enabled(), "Only the known disabled demo operator may be deleted by this test.");
            repository.deleteOperator(oliver.id());
        }
        int expectedCount = before.size() - (oliver == null ? 0 : 1);
        reopen();
        assertEquals(expectedCount, repository.findOperators().size(), "Reinitialization must not duplicate or recreate seed rows.");
        assertTrue(repository.findOperators().stream().noneMatch(operator -> operator.username().equals("oliver.grant")),
                "The seed marker must preserve deletion across application restarts.");
        repository.initialize();
        assertEquals(expectedCount, repository.findOperators().size());
    }

    @Test
    void operatorCreateUpdateAndDeletePersistAcrossReopen() {
        Operator created = createOperator("Controller", true);
        long id = created.id();
        assertTrue(id > 0);
        String username = unique("changed");
        String email = username + "@example.invalid";
        Operator updated = repository.updateOperator(id, "Updated O'Connor", username, email,
                "Admin", false, "All sectors", new char[0]);
        assertEquals(id, updated.id());
        reopen();
        Operator stored = operator(id);
        assertEquals("Updated O'Connor", stored.name());
        assertEquals(username, stored.username());
        assertEquals(email, stored.email());
        assertEquals("Admin", stored.role());
        assertFalse(stored.enabled());
        assertEquals("All sectors", stored.clearance());
        repository.deleteOperator(id);
        reopen();
        assertTrue(repository.findOperators().stream().noneMatch(operator -> operator.id() == id));
    }

    @Test
    void authenticationChecksPasswordRoleEnabledStateAndSqlInjection() throws Exception {
        Operator created = createOperator("Controller", true);
        assertEquals(created.id(), repository.authenticate(created.username(), password(), "Controller").orElseThrow().id());
        assertEquals(created.id(), repository.authenticate("  " + created.username().toUpperCase(Locale.ROOT) + "  ",
                password(), "Controller").orElseThrow().id());
        assertTrue(repository.authenticate(created.username(), "wrong-password".toCharArray(), "Controller").isEmpty());
        assertTrue(repository.authenticate(created.username(), password(), "Admin").isEmpty());
        assertTrue(repository.authenticate("' OR '1'='1' -- ", password(), "Admin").isEmpty());
        assertTrue(repository.authenticate(created.username(), "' OR '1'='1' -- ".toCharArray(), "Controller").isEmpty());
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT last_active FROM operators WHERE id = ?")) {
            statement.setLong(1, created.id());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertNotNull(result.getTimestamp(1), "Successful login must be persisted as last activity.");
            }
        }
        repository.updateOperator(created.id(), created.name(), created.username(), created.email(),
                created.role(), false, created.clearance(), new char[0]);
        reopen();
        assertTrue(repository.authenticate(created.username(), password(), "Controller").isEmpty(),
                "Disabled operators must not authenticate after a restart.");
    }

    @Test
    void passwordsAreSaltedHashesAndPasswordChangesPersist() throws Exception {
        Operator first = createOperator("Controller", true);
        Operator second = createOperator("Controller", true);
        String firstHash = storedHash(first.id());
        String secondHash = storedHash(second.id());
        assertPasswordHash(firstHash);
        assertPasswordHash(secondHash);
        assertNotEquals(firstHash, secondHash, "Equal passwords must have independent random salts.");

        repository.updateOperator(first.id(), first.name(), first.username(), first.email(), first.role(), true,
                first.clearance(), new char[0]);
        assertEquals(firstHash, storedHash(first.id()), "An empty password during an edit must preserve the stored hash.");
        String replacement = "Updated-Aerocon-73!";
        repository.updateOperator(first.id(), first.name(), first.username(), first.email(), first.role(), true,
                first.clearance(), replacement.toCharArray());
        reopen();
        String replacementHash = storedHash(first.id());
        assertPasswordHash(replacementHash);
        assertNotEquals(replacement, replacementHash);
        assertNotEquals(firstHash, replacementHash);
        assertTrue(repository.authenticate(first.username(), password(), "Controller").isEmpty());
        assertEquals(first.id(), repository.authenticate(first.username(), replacement.toCharArray(), "Controller")
                .orElseThrow().id());
    }

    @Test
    void duplicateUsernameAndEmailAreRejectedWithoutCreatingExtraRows() {
        Operator existing = createOperator("Controller", true);
        int count = repository.findOperators().size();
        String fresh = unique("duplicate");
        assertThrows(RuntimeException.class, () -> createTracked("Duplicate username", existing.username(),
                fresh + "@example.invalid", "Controller", true, "Sector 07", password()));
        assertThrows(RuntimeException.class, () -> createTracked("Duplicate email", fresh,
                existing.email(), "Controller", true, "Sector 07", password()));
        assertEquals(count, repository.findOperators().size());
        assertEquals(existing.username(), operator(existing.id()).username());
        assertEquals(existing.email(), operator(existing.id()).email());

        Operator other = createOperator("Controller", true);
        assertThrows(RuntimeException.class, () -> repository.updateOperator(other.id(), other.name(), existing.username(),
                other.email(), other.role(), other.enabled(), other.clearance(), new char[0]));
        assertThrows(RuntimeException.class, () -> repository.updateOperator(other.id(), other.name(), other.username(),
                existing.email(), other.role(), other.enabled(), other.clearance(), new char[0]));
        assertEquals(other.username(), operator(other.id()).username(), "A failed edit must leave the original row intact.");
        assertEquals(other.email(), operator(other.id()).email());
    }

    @Test
    void conflictRecordingIsIdempotentAndAcknowledgementSurvivesReopen() throws Exception {
        Operator actor = createOperator("Controller", true);
        String source = unique("incident");
        createdIncidentKeys.add(source);
        Incident created = repository.recordConflict(source, "TST101", "TST202");
        assertTrue(created.id() > 0);
        assertEquals("Open", created.status());
        assertTrue(created.flights().contains("TST101") && created.flights().contains("TST202"));
        Incident repeated = repository.recordConflict(source, "TST101", "TST202");
        assertEquals(created.id(), repeated.id(), "The same source key must not create a second incident.");
        assertEquals(1, countIncidents(source));

        repository.acknowledgeIncident(created.id(), actor.username());
        reopen();
        Incident stored = repository.findIncidents().stream().filter(incident -> incident.id() == created.id())
                .findFirst().orElseThrow();
        assertEquals("Acknowledged", stored.status());
        assertEquals(created.date(), stored.date());
        assertEquals(created.flights(), stored.flights());
        assertEquals(created.code(), stored.code());
        assertEquals(created.id(), repository.recordConflict(source, "TST101", "TST202").id());
        assertEquals("Acknowledged", repository.findIncidents().stream().filter(incident -> incident.id() == created.id())
                .findFirst().orElseThrow().status(), "Repeated recording must not reopen an acknowledged incident.");
        assertEquals(1, countIncidents(source));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT acknowledged_by, acknowledged_at FROM incidents WHERE id = ?")) {
            statement.setLong(1, created.id());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(actor.username(), result.getString(1));
                assertNotNull(result.getTimestamp(2));
            }
        }
    }

    @Test
    void lastEnabledAdministratorCannotBeDeletedDisabledOrDemoted() {
        List<Operator> originalAdmins = repository.findOperators().stream()
                .filter(operator -> operator.enabled() && operator.role().equals("Admin")).toList();
        assertFalse(originalAdmins.isEmpty(), "Initialization must leave at least one enabled administrator.");
        Operator keeper = createOperator("Admin", true);
        try {
            for (Operator admin : originalAdmins) {
                repository.updateOperator(admin.id(), admin.name(), admin.username(), admin.email(), admin.role(),
                        false, admin.clearance(), new char[0]);
            }
            assertEquals(1, repository.findOperators().stream()
                    .filter(operator -> operator.enabled() && operator.role().equals("Admin")).count());
            assertThrows(RuntimeException.class, () -> repository.deleteOperator(keeper.id()));
            assertThrows(RuntimeException.class, () -> repository.updateOperator(keeper.id(), keeper.name(), keeper.username(),
                    keeper.email(), "Admin", false, keeper.clearance(), new char[0]));
            assertThrows(RuntimeException.class, () -> repository.updateOperator(keeper.id(), keeper.name(), keeper.username(),
                    keeper.email(), "Controller", true, keeper.clearance(), new char[0]));
            assertTrue(operator(keeper.id()).enabled());
            assertEquals("Admin", operator(keeper.id()).role());
        } finally {
            // Restore every original admin even if a guard assertion fails; only the temporary keeper is later deleted.
            for (Operator admin : originalAdmins) {
                repository.updateOperator(admin.id(), admin.name(), admin.username(), admin.email(), admin.role(),
                        true, admin.clearance(), new char[0]);
            }
        }
    }

    private Operator createOperator(String role, boolean enabled) {
        String username = unique("operator");
        return createTracked("Integration Operator", username, username + "@example.invalid", role, enabled,
                role.equals("Admin") ? "All sectors" : "Sector 07 / London Upper", password());
    }

    private Operator createTracked(String name, String username, String email, String role, boolean enabled,
                                   String clearance, char[] password) {
        Operator created = repository.createOperator(name, username, email, role, enabled, clearance, password);
        createdOperatorIds.add(created.id());
        return created;
    }

    private Operator operator(long id) {
        return repository.findOperators().stream().filter(operator -> operator.id() == id).findFirst().orElseThrow();
    }

    private void reopen() {
        repository.close();
        repository = new JdbcAeroconRepository(config);
        repository.initialize();
    }

    private String storedHash(long id) throws Exception {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT password_hash FROM operators WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private int countIncidents(String source) throws Exception {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM incidents WHERE source_key = ?")) {
            statement.setString(1, source);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static void assertPasswordHash(String hash) {
        assertNotNull(hash);
        assertNotEquals(TEST_PASSWORD, hash, "Passwords must never be stored as plaintext.");
        String[] parts = hash.split("\\$");
        assertEquals(4, parts.length, "Expected algorithm, work factor, salt and derived key.");
        assertEquals("pbkdf2-sha256", parts[0]);
        assertTrue(Integer.parseInt(parts[1]) >= 100_000, "Password hashing must use a substantial work factor.");
        assertTrue(Base64.getDecoder().decode(parts[2]).length >= 16);
        assertTrue(Base64.getDecoder().decode(parts[3]).length >= 32);
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static char[] password() {
        return TEST_PASSWORD.toCharArray();
    }

    private static String unique(String prefix) {
        return "it_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private static String configured(String key, String suffix, Properties file) {
        String value = setting("aerocon.test.db." + key, "AEROCON_TEST_DB_" + suffix);
        return value == null ? file.getProperty("db." + key) : value;
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null) value = System.getProperty(environment);
        return value == null ? System.getenv(environment) : value;
    }
}
