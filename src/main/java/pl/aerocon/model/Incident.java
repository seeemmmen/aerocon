package pl.aerocon.model;

import java.time.LocalDate;

public record Incident(long id, LocalDate date, String time, String flights, String severity,
                       String detail, String status, String sector) {
    public String code() { return String.format("INC-%04d", id); }
}
