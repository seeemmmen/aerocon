package pl.aerocon.model;

/** Public account details. Password hashes never enter the UI model. */
public record Operator(long id, String name, String username, String email, String role,
                       boolean enabled, String lastActive, String clearance) { }
