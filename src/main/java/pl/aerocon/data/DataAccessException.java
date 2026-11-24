package pl.aerocon.data;

/** A storage failure with a message suitable for displaying in the application. */
public final class DataAccessException extends RuntimeException {
    public DataAccessException(String message) { super(message); }
    public DataAccessException(String message, Throwable cause) { super(message, cause); }
}
