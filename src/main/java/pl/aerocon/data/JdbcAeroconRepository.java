package pl.aerocon.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import pl.aerocon.model.Incident;
import pl.aerocon.model.Operator;

/** Small JDBC repository. Each operation owns its connection and transaction. */
public final class JdbcAeroconRepository implements AeroconRepository {
    private static final DateTimeFormatter LAST_ACTIVE = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private final DatabaseConfig config;

    public JdbcAeroconRepository(DatabaseConfig config) { this.config = Objects.requireNonNull(config); }

    private Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.user()); properties.setProperty("password", config.password());
        properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "10000");
        Connection connection = DriverManager.getConnection(config.url(), properties);
        try (Statement statement = connection.createStatement()) { statement.execute("SET time_zone = '+00:00'"); }
        catch (SQLException ex) { connection.close(); throw ex; }
        return connection;
    }

    @Override public void initialize() {
        try (Connection connection = connect()) {
            String schema;
            try (var stream = getClass().getResourceAsStream("/pl/aerocon/db/schema.sql")) {
                if (stream == null) throw new IOException("Missing schema resource");
                schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String command : schema.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!command.isBlank()) try (Statement statement = connection.createStatement()) { statement.execute(command); }
            }
            transaction(connection, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT IGNORE INTO app_metadata(meta_key,meta_value) VALUES ('seed_v1','pending')");
                    try (ResultSet result = statement.executeQuery("SELECT meta_value FROM app_metadata WHERE meta_key='seed_v1' FOR UPDATE")) {
                        result.next(); if (result.getString(1).equals("complete")) return null;
                    }
                }
                seed(connection);
                try (Statement statement = connection.createStatement()) { statement.executeUpdate("UPDATE app_metadata SET meta_value='complete' WHERE meta_key='seed_v1'"); }
                return null;
            });
        } catch (SQLException ex) { throw failure(ex); }
        catch (IOException ex) { throw new DataAccessException("The database schema could not be loaded.", ex); }
    }

    private void seed(Connection connection) throws SQLException {
        String[][] users = {
            {"Maya Chen", "maya.chen", "Controller", "Sector 07 / London Upper"},
            {"Sarah Patel", "sarah.patel", "Admin", "All sectors"},
            {"James Wilson", "james.wilson", "Controller", "Sector 07 / London Upper"},
            {"Elena Rossi", "elena.rossi", "Controller", "Sector 07 / London Upper"},
            {"Noah Brooks", "noah.brooks", "Admin", "All sectors"},
            {"Oliver Grant", "oliver.grant", "Controller", "Sector 04 / London Lower"}
        };
        try (PreparedStatement insert = connection.prepareStatement("INSERT IGNORE INTO operators(name,username,email,role,enabled,clearance,password_hash) VALUES (?,?,?,?,?,?,?)")) {
            for (String[] user : users) {
                insert.setString(1, user[0]); insert.setString(2, user[1]); insert.setString(3, user[1] + "@aerocon.atc");
                insert.setString(4, user[2]); insert.setBoolean(5, !user[1].equals("oliver.grant"));
                insert.setString(6, user[3]); insert.setString(7, PasswordHasher.hash("aerocon".toCharArray())); insert.addBatch();
            }
            insert.executeBatch();
        }
        String[][] incidents = {
            {"sample-0041", "BAW452", "AAL217", "Open", "Demo scenario: predicted separation 0.8 NM / 400 ft"},
            {"sample-0040", "DLH908", "UAE031", "Resolved", "Demo scenario: separation restored to 6.2 NM / 2,000 ft"},
            {"sample-0039", "AFR168", "KLM602", "Resolved", "Demo scenario: separation restored to 8.1 NM / 4,000 ft"}
        };
        try (PreparedStatement insert = connection.prepareStatement("INSERT IGNORE INTO incidents(source_key,callsign_a,callsign_b,severity,detail,status,sector) VALUES (?,?,?,'Critical',?,?,'Sector 07')")) {
            for (String[] incident : incidents) {
                insert.setString(1, incident[0]); insert.setString(2, incident[1]); insert.setString(3, incident[2]);
                insert.setString(4, incident[4]); insert.setString(5, incident[3]); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Override public List<Operator> findOperators() {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM operators ORDER BY id"); ResultSet result = statement.executeQuery()) {
            List<Operator> users = new ArrayList<>(); while (result.next()) users.add(operator(result)); return users;
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public Operator createOperator(String name, String username, String email, String role, boolean enabled, String clearance, char[] password) {
        validateUser(name, username, email, role, clearance); validatePassword(password);
        String hash = PasswordHasher.hash(password);
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO operators(name,username,email,role,enabled,clearance,password_hash) VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            bindUser(insert, name, username, email, role, enabled, clearance); insert.setString(7, hash); insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) { keys.next(); return findOperator(connection, keys.getLong(1)); }
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public Operator updateOperator(long id, String name, String username, String email, String role, boolean enabled, String clearance, char[] newPassword) {
        validateUser(name, username, email, role, clearance);
        String hash = null;
        if (newPassword != null && newPassword.length > 0) { validatePassword(newPassword); hash = PasswordHasher.hash(newPassword); }
        final String changedHash = hash;
        try (Connection connection = connect()) {
            return transaction(connection, () -> {
                Set<Long> admins = lockAdmins(connection);
                Operator current = findOperator(connection, id);
                if (current.role().equals("Admin") && current.enabled() && (!enabled || !role.equals("Admin")) && admins.size() <= 1) {
                    throw new DataAccessException("Keep at least one enabled administrator account.");
                }
                try (PreparedStatement update = connection.prepareStatement("UPDATE operators SET name=?,username=?,email=?,role=?,enabled=?,clearance=?,password_hash=COALESCE(?,password_hash) WHERE id=?")) {
                    bindUser(update, name, username, email, role, enabled, clearance); update.setString(7, changedHash); update.setLong(8, id); update.executeUpdate();
                }
                return findOperator(connection, id);
            });
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public void deleteOperator(long id) {
        try (Connection connection = connect()) {
            transaction(connection, () -> {
                Set<Long> admins = lockAdmins(connection); Operator current = findOperator(connection, id);
                if (current.role().equals("Admin") && current.enabled() && admins.size() <= 1) throw new DataAccessException("Keep at least one enabled administrator account.");
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM operators WHERE id=?")) { delete.setLong(1, id); delete.executeUpdate(); }
                return null;
            });
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public Optional<Operator> authenticate(String login, char[] password, String role) {
        if (login == null || password == null || role == null || !List.of("Controller", "Admin").contains(role)) return Optional.empty();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement("SELECT * FROM operators WHERE username=?")) {
            query.setString(1, login.strip().toLowerCase(Locale.ROOT));
            Operator user; String hash;
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return Optional.empty(); user = operator(result); hash = result.getString("password_hash");
            }
            if (!PasswordHasher.verify(password, hash) || !user.enabled() || !user.role().equals(role)) return Optional.empty();
            try (PreparedStatement update = connection.prepareStatement("UPDATE operators SET last_active=CURRENT_TIMESTAMP WHERE id=? AND enabled=TRUE AND role=? AND password_hash=?")) {
                update.setLong(1, user.id()); update.setString(2, role); update.setString(3, hash);
                if (update.executeUpdate() != 1) return Optional.empty();
            }
            return Optional.of(findOperator(connection, user.id()));
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public List<Incident> findIncidents() {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM incidents ORDER BY occurred_at DESC,id DESC"); ResultSet result = statement.executeQuery()) {
            List<Incident> incidents = new ArrayList<>(); while (result.next()) incidents.add(incident(result)); return incidents;
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public Incident recordConflict(String sourceKey, String callsignA, String callsignB) {
        if (sourceKey == null || sourceKey.isBlank() || sourceKey.length() > 191 || !validCallsign(callsignA) || !validCallsign(callsignB)) throw new DataAccessException("Invalid conflict record.");
        try (Connection connection = connect()) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO incidents(source_key,callsign_a,callsign_b,severity,detail,status,sector) VALUES (?,?,?,'Critical','Demo scenario: predicted separation 0.8 NM / 400 ft','Open','Sector 07') ON DUPLICATE KEY UPDATE source_key=VALUES(source_key)")) {
                insert.setString(1, sourceKey); insert.setString(2, callsignA); insert.setString(3, callsignB); insert.executeUpdate();
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM incidents WHERE source_key=?")) {
                query.setString(1, sourceKey); try (ResultSet result = query.executeQuery()) { result.next(); return incident(result); }
            }
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public void acknowledgeIncident(long id, String actorUsername) {
        if (actorUsername == null || actorUsername.isBlank() || actorUsername.length() > 64) throw new DataAccessException("An operator is required to acknowledge an incident.");
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement("UPDATE incidents SET status='Acknowledged',acknowledged_by=?,acknowledged_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='Open'")) {
            update.setString(1, actorUsername); update.setLong(2, id);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement query = connection.prepareStatement("SELECT id FROM incidents WHERE id=?")) {
                    query.setLong(1, id); try (ResultSet result = query.executeQuery()) { if (!result.next()) throw new DataAccessException("This incident no longer exists."); }
                }
            }
        } catch (SQLException ex) { throw failure(ex); }
    }

    private Operator findOperator(Connection connection, long id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM operators WHERE id=?")) {
            query.setLong(1, id); try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new DataAccessException("This operator no longer exists. Refresh the user list.");
                return operator(result);
            }
        }
    }

    private Set<Long> lockAdmins(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT id FROM operators WHERE role='Admin' AND enabled=TRUE ORDER BY id FOR UPDATE"); ResultSet result = query.executeQuery()) {
            Set<Long> ids = new HashSet<>(); while (result.next()) ids.add(result.getLong(1)); return ids;
        }
    }

    private Operator operator(ResultSet result) throws SQLException {
        Timestamp active = result.getTimestamp("last_active", utcCalendar());
        return new Operator(result.getLong("id"), result.getString("name"), result.getString("username"), result.getString("email"), result.getString("role"),
            result.getBoolean("enabled"), active == null ? "Never signed in" : LAST_ACTIVE.format(active.toInstant()), result.getString("clearance"));
    }

    private Incident incident(ResultSet result) throws SQLException {
        var time = result.getTimestamp("occurred_at", utcCalendar()).toInstant().atOffset(ZoneOffset.UTC);
        return new Incident(result.getLong("id"), time.toLocalDate(), time.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            result.getString("callsign_a") + " / " + result.getString("callsign_b"), result.getString("severity"), result.getString("detail"), result.getString("status"), result.getString("sector"));
    }

    private static Calendar utcCalendar() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }

    private void bindUser(PreparedStatement statement, String name, String username, String email, String role, boolean enabled, String clearance) throws SQLException {
        statement.setString(1, name.strip()); statement.setString(2, username.strip().toLowerCase(Locale.ROOT)); statement.setString(3, email.strip().toLowerCase(Locale.ROOT));
        statement.setString(4, role); statement.setBoolean(5, enabled); statement.setString(6, clearance.strip());
    }

    private void validateUser(String name, String username, String email, String role, String clearance) {
        if (name == null || name.isBlank() || name.strip().length() > 120) throw new DataAccessException("Enter a name of up to 120 characters.");
        if (username == null || !username.strip().matches("[A-Za-z0-9._-]{3,64}")) throw new DataAccessException("Username must contain 3–64 letters, digits, dots, underscores or hyphens.");
        if (email == null || email.strip().length() > 160 || !email.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new DataAccessException("Enter a valid email address of up to 160 characters.");
        if (role == null || !List.of("Controller", "Admin").contains(role)) throw new DataAccessException("Choose Controller or Admin.");
        if (clearance == null || clearance.isBlank() || clearance.strip().length() > 160) throw new DataAccessException("Enter an access clearance of up to 160 characters.");
    }

    private void validatePassword(char[] password) { if (password == null || password.length < 8 || password.length > 128) throw new DataAccessException("Use a password of 8–128 characters."); }
    private boolean validCallsign(String callsign) { return callsign != null && callsign.matches("[A-Z0-9]{2,16}"); }

    private <T> T transaction(Connection connection, SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try { T value = work.run(); connection.commit(); return value; }
        catch (SQLException | RuntimeException ex) { try { connection.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); } throw ex; }
        finally { connection.setAutoCommit(true); }
    }

    @FunctionalInterface private interface SqlWork<T> { T run() throws SQLException; }

    private DataAccessException failure(SQLException ex) {
        if (ex.getErrorCode() == 1062) return new DataAccessException("A user with that username or email already exists.", ex);
        if (ex.getSQLState() != null && ex.getSQLState().startsWith("08")) return new DataAccessException("Cannot connect to the database. Start MySQL in XAMPP and check the local database settings.", ex);
        if (ex.getErrorCode() == 1045 || ex.getErrorCode() == 1044) return new DataAccessException("Database access was denied. Check the local database account and permissions.", ex);
        if (ex.getErrorCode() == 1049) return new DataAccessException("The configured database does not exist. Create it using the database setup instructions.", ex);
        return new DataAccessException("The database operation failed. Please retry or check the database connection.", ex);
    }
}
