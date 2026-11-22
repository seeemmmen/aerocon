package pl.aerocon.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import pl.aerocon.model.Flight;
import static pl.aerocon.ui.Ui.*;

public final class SimulationDialogs {
    private SimulationDialogs() {}

    public static void addFlight(Window owner, List<Flight> flights, Consumer<Flight> onAdd,
                                 Consumer<Consumer<double[]>> pickOnRadar) {
        Dialog<ButtonType> dialog = dialog(owner, "Add Flight to Simulation", "Add Flight");
        TextField callsign = input("", "e.g. LOT281"); callsign.setId("callsign-field");
        ComboBox<String> type = combo("Airbus A320", "Airbus A320", "Airbus A321", "Boeing 737-800", "Boeing 777-200", "Airbus A380");
        TextField origin = input("EGLL", "ICAO"), destination = input("EHAM", "ICAO");
        TextField altitude = input("34000", "FT"), speed = input("450", "KT");
        TextField heading = input("090", "DEG"), squawk = input("4521", "4 octal digits");
        TextField lat = input("51.4700", "Latitude"), lon = input("-0.4543", "Longitude");
        Label error = text("", "error"); error.setWrapText(true);
        Button pick = button("⌖  Pick on radar", "text-button", () -> {
            dialog.hide();
            pickOnRadar.accept(position -> {
                lat.setText(String.format(Locale.ROOT, "%.4f", 51.47 + (.5 - position[1]) * .65));
                lon.setText(String.format(Locale.ROOT, "%.4f", -.4543 + (position[0] - .5)));
                dialog.show();
            });
        });
        GridPane grid = new GridPane(); grid.setHgap(20); grid.setVgap(18);
        for (int i = 0; i < 2; i++) { ColumnConstraints col = new ColumnConstraints(); col.setPercentWidth(50); grid.getColumnConstraints().add(col); }
        Node[][] fields = {{field("Callsign", callsign), field("Aircraft type", type)},
            {field("Origin ICAO", origin), field("Destination ICAO", destination)},
            {field("Initial altitude · FT", altitude), field("Groundspeed · KT", speed)},
            {field("Heading · DEG", heading), field("Squawk code", squawk)},
            {field("Latitude", lat), field("Longitude", lon)}};
        for (int r = 0; r < fields.length; r++) for (int c = 0; c < 2; c++) grid.add(fields[r][c], c, r);
        VBox content = new VBox(16, text("SIMULATION / NEW TRACK", "eyebrow", "accent"),
            text("Define an aircraft and its initial position in the sector.", "muted"), grid,
            row(8, text("INITIAL COORDINATES", "eyebrow"), spacer(), pick),
            text("Aircraft will enter the current training simulation at these coordinates.", "note", "tiny"), error);
        content.setPadding(new Insets(8, 20, 0, 20)); content.setPrefWidth(500);
        dialog.getDialogPane().setContent(content);
        Button submit = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); submit.setId("confirm-flight");
        submit.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                String code = callsign.getText().strip().toUpperCase(Locale.ROOT);
                if (!code.matches("[A-Z]{2,3}[0-9]{1,4}[A-Z]?")) throw new IllegalArgumentException("Use a callsign such as LOT281 or BAW452.");
                if (flights.stream().anyMatch(f -> f.callsign.equals(code))) throw new IllegalArgumentException("This callsign is already being tracked.");
                String from = origin.getText().strip().toUpperCase(Locale.ROOT), to = destination.getText().strip().toUpperCase(Locale.ROOT);
                if (!from.matches("[A-Z]{4}") || !to.matches("[A-Z]{4}")) throw new IllegalArgumentException("Origin and destination must be four-letter ICAO codes.");
                int alt = integer(altitude, 1000, 60000, "Altitude"), gs = integer(speed, 100, 650, "Groundspeed");
                int hdg = integer(heading, 0, 359, "Heading");
                if (!squawk.getText().matches("[0-7]{4}")) throw new IllegalArgumentException("Squawk must contain four digits between 0 and 7.");
                double latitude = decimal(lat, -90, 90, "Latitude"), longitude = decimal(lon, -180, 180, "Longitude");
                double x = .5 + longitude + .4543, y = .5 - (latitude - 51.47) / .65;
                if (x < .04 || x > .96 || y < .04 || y > .96) throw new IllegalArgumentException("Position is outside this training sector. Use Pick on radar.");
                String aircraft = type.getValue().startsWith("Airbus") ? type.getValue().replace("Airbus ", "") : type.getValue().contains("737") ? "B738" : "B772";
                onAdd.accept(new Flight(code, "Training flight", aircraft, from, to, alt, gs, hdg, squawk.getText(), x, y, false));
            } catch (IllegalArgumentException ex) { error.setText(ex.getMessage()); event.consume(); }
        });
        dialog.show();
    }

    public static void generate(Window owner, List<Flight> flights, Consumer<String> onDone) {
        Dialog<ButtonType> dialog = dialog(owner, "Generate Synthetic Traffic", "Generate Traffic");
        ComboBox<String> scenario = combo("Normal Sector Load", "Normal Sector Load", "High Density", "Conflict Stress Test");
        Slider count = new Slider(1, 50, 12); count.setMajorTickUnit(1); count.setSnapToTicks(true); count.setBlockIncrement(1);
        Label countLabel = text("12 tracks", "mono", "accent");
        count.valueProperty().addListener((o, before, after) -> countLabel.setText(Math.round(after.doubleValue()) + " tracks"));
        CheckBox conflict = new CheckBox("Inject a deliberate collision conflict");
        CheckBox altitudes = new CheckBox("Randomize altitudes (FL280–FL390)"); altitudes.setSelected(true);
        CheckBox commercial = new CheckBox("Include commercial & cargo flights"); commercial.setSelected(true);
        CheckBox clear = new CheckBox("Clear existing flights before generating");
        scenario.setOnAction(e -> {
            if (scenario.getValue().equals("High Density")) count.setValue(30);
            if (scenario.getValue().equals("Conflict Stress Test")) { conflict.setSelected(true); if (count.getValue() < 2) count.setValue(12); }
        });
        Label error = text("", "error");
        VBox content = new VBox(20, text("SIMULATION / TRAFFIC GENERATOR", "eyebrow", "accent"),
            text("Populate your sector with configurable training traffic.", "muted"), field("Scenario preset", scenario),
            row(8, text("Number of aircraft", "muted"), spacer(), countLabel), count,
            row(0, text("1", "eyebrow"), spacer(), text("50", "eyebrow")), new Separator(),
            text("TRAFFIC BEHAVIOR", "eyebrow"), conflict, altitudes, commercial, new Separator(), clear, error);
        content.setPadding(new Insets(8, 20, 0, 20)); content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);
        Button submit = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); submit.setId("confirm-generate");
        submit.addEventFilter(ActionEvent.ACTION, event -> {
            int n = (int) Math.round(count.getValue());
            if (conflict.isSelected() && n < 2) { error.setText("At least two aircraft are needed for a conflict scenario."); event.consume(); return; }
            if (clear.isSelected()) flights.clear();
            Random random = new Random();
            Set<String> used = new HashSet<>(); flights.forEach(f -> used.add(f.callsign));
            String[] prefixes = commercial.isSelected() ? new String[]{"BAW", "LOT", "DLH", "KLM", "AFR", "FDX", "SAS", "UAE"} : new String[]{"TST"};
            for (int i = 0; i < n; i++) {
                String callsign;
                do { callsign = prefixes[random.nextInt(prefixes.length)] + (100 + random.nextInt(8900)); } while (!used.add(callsign));
                boolean danger = conflict.isSelected() && i < 2;
                double x = .10 + random.nextDouble() * .73, y = .13 + random.nextDouble() * .68;
                int hdg = random.nextInt(360), altitude = altitudes.isSelected() ? (28 + random.nextInt(12)) * 1000 : 34000;
                if (danger) { x = i == 0 ? .36 : .59; y = i == 0 ? .35 : .64; hdg = i == 0 ? 90 : 0; altitude = 34000; }
                flights.add(new Flight(callsign, commercial.isSelected() ? "Synthetic commercial traffic" : "Training flight", "A320", "EGLL", "EHAM", altitude,
                    420 + random.nextInt(70), hdg, String.format("%04o", random.nextInt(4096)), x, y, danger));
            }
            onDone.accept(scenario.getValue() + " · " + n + " aircraft generated");
        });
        dialog.show();
    }

    private static Dialog<ButtonType> dialog(Window owner, String title, String action) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner); dialog.setTitle(title); dialog.setHeaderText(title);
        dialog.getDialogPane().getStylesheets().add(SimulationDialogs.class.getResource("/pl/aerocon/aerocon.css").toExternalForm());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); ok.setText(action); ok.getStyleClass().add("primary-button");
        return dialog;
    }

    private static ComboBox<String> combo(String value, String... values) {
        ComboBox<String> combo = new ComboBox<>(); combo.getItems().addAll(values); combo.setValue(value); combo.setMaxWidth(Double.MAX_VALUE); return combo;
    }

    private static int integer(TextField field, int min, int max, String name) {
        try { int value = Integer.parseInt(field.getText().strip()); if (value >= min && value <= max) return value; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be a whole number from " + min + " to " + max + ".");
    }

    private static double decimal(TextField field, double min, double max, String name) {
        try { double value = Double.parseDouble(field.getText().strip()); if (Double.isFinite(value) && value >= min && value <= max) return value; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be a number from " + min + " to " + max + ".");
    }
}
