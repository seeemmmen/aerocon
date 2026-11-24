package pl.aerocon.data;

import java.util.List;
import java.util.Optional;
import pl.aerocon.model.Incident;
import pl.aerocon.model.Operator;

public interface AeroconRepository extends AutoCloseable {
    void initialize();
    List<Operator> findOperators();
    Operator createOperator(String name, String username, String email, String role, boolean enabled,
                            String clearance, char[] password);
    Operator updateOperator(long id, String name, String username, String email, String role,
                            boolean enabled, String clearance, char[] newPassword);
    void deleteOperator(long id);
    Optional<Operator> authenticate(String login, char[] password, String role);
    List<Incident> findIncidents();
    Incident recordConflict(String sourceKey, String callsignA, String callsignB);
    void acknowledgeIncident(long id, String actorUsername);
    @Override default void close() { }
}
