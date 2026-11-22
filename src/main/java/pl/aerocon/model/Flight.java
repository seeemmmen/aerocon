package pl.aerocon.model;

import java.util.ArrayList;
import java.util.List;

/** A training track. Positions are normalized to the displayed sector. */
public final class Flight {
    public final String callsign, airline, type, origin, destination, squawk;
    public final int altitude, speed, heading;
    public final boolean conflict;
    public double x, y;

    public Flight(String callsign, String airline, String type, String origin, String destination,
                  int altitude, int speed, int heading, String squawk, double x, double y,
                  boolean conflict) {
        this.callsign = callsign;
        this.airline = airline;
        this.type = type;
        this.origin = origin;
        this.destination = destination;
        this.altitude = altitude;
        this.speed = speed;
        this.heading = heading;
        this.squawk = squawk;
        this.x = x;
        this.y = y;
        this.conflict = conflict;
    }

    public String flightLevel() { return "FL" + altitude / 100; }

    public static List<Flight> demoFlights() {
        return new ArrayList<>(List.of(
            new Flight("BAW452", "British Airways", "A320", "EGLL", "EHAM", 34000, 450, 90, "4521", .36, .35, true),
            new Flight("AAL217", "American Airlines", "B772", "KJFK", "EGLL", 34000, 430, 0, "2170", .59, .64, true),
            new Flight("DLH908", "Lufthansa", "A321", "EDDF", "EGLL", 37000, 462, 100, "3452", .20, .17, false),
            new Flight("UAE031", "Emirates", "A388", "OMDB", "EGLL", 36000, 480, 240, "6214", .76, .20, false),
            new Flight("AFR168", "Air France", "A320", "LFPG", "EGCC", 32000, 440, 65, "4120", .09, .58, false),
            new Flight("KLM602", "KLM", "B738", "EHAM", "EGLL", 35000, 455, 285, "5631", .77, .79, false),
            new Flight("SAS864", "Scandinavian Airlines", "A320", "EKCH", "EGLL", 33000, 425, 275, "2517", .32, .84, false),
            new Flight("SWR116", "Swiss", "A220", "LSZH", "EGLL", 38000, 470, 260, "1402", .89, .49, false)
        ));
    }
}
