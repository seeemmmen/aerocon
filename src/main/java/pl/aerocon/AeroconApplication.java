package pl.aerocon;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import pl.aerocon.model.Flight;
import pl.aerocon.ui.AdminView;
import pl.aerocon.ui.RadarView;
import pl.aerocon.ui.SimulationDialogs;
import static pl.aerocon.ui.Ui.*;

/** Visual, local-only prototype of the Aerocon training interface. */
public final class AeroconApplication extends Application {
    private final List<Flight> flights = Flight.demoFlights();
    private final BorderPane shell = new BorderPane();
    private final StackPane root = new StackPane(shell);
    private Stage stage;
    private Timeline clock;
    private RadarView radar;
    private VBox flightRows, details, warnings;
    private Label elapsedLabel, flightCount, trackCount, alertCount, utcClock, statusLabel;
    private Button pauseButton;
    private Flight selected = flights.getFirst();
    private boolean running = true, loggedIn, administrator, trajectory;
    private final Set<String> acknowledgedPairs = new HashSet<>();
    private double elapsed = 18 * 60 + 42, playback = 1, sweep;
    private int range = 15;
    private String username = "Maya Chen", scenario = "London upper airspace";
    private AdminView adminView;

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        this.stage = stage;
        Scene scene = new Scene(root, 1440, 900);
        scene.getStylesheets().add(getClass().getResource("/pl/aerocon/aerocon.css").toExternalForm());
        stage.setTitle("Aerocon — Air Traffic Control / Training");
        stage.setScene(scene); stage.setMinWidth(1120); stage.setMinHeight(740);
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(1440, bounds.getWidth() - 60));
        stage.setHeight(Math.min(940, bounds.getHeight() - 60));
        shell.setBottom(footer());
        Map<String, String> parameters = getParameters() == null ? Map.of() : getParameters().getNamed();
        if (parameters.containsKey("width")) stage.setWidth(Double.parseDouble(parameters.get("width")));
        if (parameters.containsKey("height")) stage.setHeight(Double.parseDouble(parameters.get("height")));
        String view = parameters.getOrDefault("view", "login");
        if (view.equals("radar") || view.equals("admin")) {
            loggedIn = true; administrator = view.equals("admin"); username = administrator ? "Sarah Patel" : "Maya Chen";
            if (administrator) showAdmin(); else showRadar();
        } else showLogin();
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        clock.setCycleCount(Timeline.INDEFINITE); clock.play();
        stage.show(); stage.centerOnScreen();
        root.setFocusTraversable(true); root.requestFocus();
        String screenshot = parameters.get("snapshot");
        if (screenshot != null) {
            PauseTransition wait = new PauseTransition(Duration.millis(700));
            wait.setOnFinished(e -> { saveScreenshot(screenshot); Platform.exit(); }); wait.play();
        }
    }

    @Override public void stop() { if (clock != null) clock.stop(); }

    private HBox header(String workspace) {
        Label mark = text("◎", "brand-mark");
        Label brand = text("aerocon", "brand");
        HBox header = row(12, mark, brand);
        header.getStyleClass().add("header"); header.setPadding(new Insets(16, 26, 16, 26));
        if (loggedIn) header.getChildren().addAll(text(" / ", "muted"), text(workspace, "muted", "tiny"));
        header.getChildren().add(spacer());
        if (!loggedIn) header.getChildren().add(text("SECURE ACCESS GATEWAY", "eyebrow"));
        header.getChildren().add(text("● SYSTEM ONLINE", "badge"));
        if (loggedIn) {
            alertCount = text("1 ACTIVE ALERT", "badge", "red");
            if (!administrator) header.getChildren().add(alertCount);
            utcClock = text(utc(), "eyebrow");
            header.getChildren().addAll(utcClock, text(username.equals("Sarah Patel") ? "SP" : "MC", "avatar"));
            MenuButton user = new MenuButton(username + "  ⌄"); user.getStyleClass().add("user-menu");
            MenuItem signOut = new MenuItem("Log out"); signOut.setOnAction(e -> showLogin());
            if (administrator) {
                MenuItem admin = new MenuItem("Administration"); admin.setOnAction(e -> showAdmin()); user.getItems().add(admin);
            }
            user.getItems().add(signOut);
            VBox account = new VBox(0, user, text(administrator ? "ADMINISTRATOR" : "CONTROLLER", "eyebrow"));
            header.getChildren().add(account);
        }
        return header;
    }

    private HBox footer() {
        HBox footer = row(16, text("AEROCON / ATC SIMULATION"), text("·"), text("BUILD 0.1.0"), spacer(),
            text("TRAINING ENVIRONMENT"), text("·"), text("NOT FOR OPERATIONAL USE"));
        footer.setPadding(new Insets(10, 24, 10, 24)); footer.getStyleClass().add("footer"); return footer;
    }

    private void showLogin() {
        loggedIn = false; radar = null; flightRows = null; warnings = null; details = null;
        shell.setTop(header(""));
        Label hero = text("Clarity in every\ncontrolled airspace.", "hero-title");
        Label copy = text("Monitor movement. Anticipate conflict. Maintain separation.\nOne coordinated view of the airspace.", "hero-copy");
        List<Flight> preview = new ArrayList<>();
        preview.add(new Flight("AEROCON / SECTOR 07", "", "", "", "", 34000, 450, 65, "", .44, .46, false));
        RadarView previewRadar = new RadarView(preview, f -> {}); previewRadar.setMinHeight(230); previewRadar.setPrefHeight(340);
        previewRadar.setSelected(preview.getFirst()); previewRadar.setTrajectoryVisible(true);
        VBox.setVgrow(previewRadar, Priority.ALWAYS);
        HBox features = row(22, text("◎  Live surveillance", "muted", "tiny"), text("⌁  Conflict detection", "muted", "tiny"), text("◇  Role-based access", "muted", "tiny"));
        VBox left = new VBox(22, text("—  AIR TRAFFIC CONTROL / SIMULATION SUITE", "eyebrow", "accent"), hero, copy, previewRadar, features);
        left.getStyleClass().add("login-left"); left.setPadding(new Insets(52, 48, 58, 48));
        left.setAlignment(Pos.CENTER_LEFT); left.setMaxHeight(780);
        TextField login = input("maya.chen", "Username"); login.setId("username-field");
        PasswordField password = new PasswordField(); password.setText("aerocon"); password.setId("password-field");
        TextField visiblePassword = input("", "Password"); visiblePassword.textProperty().bindBidirectional(password.textProperty());
        visiblePassword.setVisible(false); visiblePassword.setManaged(false);
        StackPane passwordStack = new StackPane(password, visiblePassword); HBox.setHgrow(passwordStack, Priority.ALWAYS);
        Button reveal = button("◉", "icon-button", () -> {
            boolean show = !visiblePassword.isVisible(); visiblePassword.setVisible(show); visiblePassword.setManaged(show);
            password.setVisible(!show); password.setManaged(!show);
        }); reveal.setTooltip(new Tooltip("Show or hide password"));
        ComboBox<String> role = new ComboBox<>(); role.getItems().addAll("Controller", "Admin"); role.setValue("Controller"); role.setId("role-select");
        Label error = text("", "error"); error.setWrapText(true); error.setManaged(false);
        Button submit = button("→  Log In", "primary-button", () -> {
            if (!password.getText().equals("aerocon") || !List.of("maya.chen", "sarah.patel").contains(login.getText().strip().toLowerCase(Locale.ROOT))) {
                error.setText("Use maya.chen or sarah.patel with password aerocon."); error.setManaged(true); return;
            }
            administrator = role.getValue().equals("Admin"); loggedIn = true;
            username = login.getText().strip().equalsIgnoreCase("sarah.patel") ? "Sarah Patel" : "Maya Chen";
            if (administrator) showAdmin(); else showRadar();
        });
        submit.setId("login-button"); submit.setMaxWidth(Double.MAX_VALUE); submit.setDefaultButton(true);
        Label authorized = text("♙  Authorized personnel only. All access attempts are\n     recorded and monitored.", "muted", "tiny");
        VBox form = new VBox(20,
            text("01 / OPERATOR ACCESS", "eyebrow", "accent"),
            text("Log in to Aerocon", "title"),
            text("Enter your assigned credentials to access\nthe air traffic control environment.", "muted"),
            field("Username", login), field("Password", row(4, passwordStack, reveal)), field("Role", role),
            text("Available roles: Controller / Admin", "muted", "tiny"), submit, error, new Separator(), authorized,
            text("Demo access: maya.chen / aerocon", "eyebrow"),
            text("Need access? Contact your system administrator.", "muted", "tiny"));
        form.setMaxWidth(350); form.setPadding(new Insets(24, 0, 24, 0));
        StackPane right = new StackPane(form); right.setPadding(new Insets(32, 50, 32, 50)); right.getStyleClass().add("login-form");
        GridPane columns = new GridPane();
        ColumnConstraints a = new ColumnConstraints(); a.setPercentWidth(58);
        ColumnConstraints b = new ColumnConstraints(); b.setPercentWidth(42);
        columns.getColumnConstraints().addAll(a, b); columns.add(left, 0, 0); columns.add(right, 1, 0);
        RowConstraints row = new RowConstraints(); row.setVgrow(Priority.ALWAYS); columns.getRowConstraints().add(row);
        columns.setMinHeight(700);
        shell.setCenter(responsiveScroll(columns));
    }

    private void showRadar() {
        shell.setTop(header("Controller workspace"));
        radar = new RadarView(flights, this::selectFlight); radar.setSelected(selected); radar.setRange(range);
        radar.setTrajectoryVisible(trajectory);
        VBox left = new VBox(16, simulationControls(), trackedFlights(), row(5, text("↔", "accent"), text("FEED CONNECTED / 24 MS", "eyebrow")));
        left.setPrefWidth(210); left.setMinWidth(195); left.setMaxWidth(210);
        VBox center = radarPanel(); HBox.setHgrow(center, Priority.ALWAYS);
        warnings = new VBox(0); warnings.getStyleClass().add("panel");
        details = new VBox(15); details.setPadding(new Insets(17)); details.getStyleClass().add("panel");
        VBox rightContent = new VBox(16, warnings, details);
        ScrollPane right = new ScrollPane(rightContent); right.setFitToWidth(true); right.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        right.setMinWidth(285); right.setPrefWidth(300); right.setMaxWidth(300);
        HBox body = new HBox(16, left, center, right); VBox.setVgrow(body, Priority.ALWAYS);
        flightCount = text("", "eyebrow");
        HBox heading = row(18, text("Airspace overview", "title"), text("SECTOR 07   /   LONDON UPPER", "eyebrow"), spacer(), flightCount);
        if (administrator) heading.getChildren().add(button("Administration →", "secondary-button", this::showAdmin));
        VBox workspace = new VBox(22, heading, body); workspace.setPadding(new Insets(24));
        workspace.setMinHeight(680);
        shell.setCenter(responsiveScroll(workspace)); refreshFlights();
    }

    private ScrollPane responsiveScroll(Region content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true); scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private VBox simulationControls() {
        statusLabel = text(running ? "● RUNNING" : "Ⅱ PAUSED", "badge");
        Button add = button("＋  Add Flight", "primary-button", this::openAddFlight); add.setId("add-flight"); add.setMaxWidth(Double.MAX_VALUE);
        Button generate = button("▤  Generate Test Data", "secondary-button", () -> SimulationDialogs.generate(stage, flights, value -> {
            scenario = value.split(" · ")[0];
            acknowledgedPairs.removeIf(pair -> !currentPairKeys().contains(pair));
            if (!flights.contains(selected)) selected = flights.isEmpty() ? null : flights.getFirst();
            showRadar(); notice(value);
        })); generate.setId("generate-traffic"); generate.setMaxWidth(Double.MAX_VALUE);
        pauseButton = button(running ? "Ⅱ  Pause" : "▷  Resume", "secondary-button", () -> {
            running = !running; pauseButton.setText(running ? "Ⅱ  Pause" : "▷  Resume"); statusLabel.setText(running ? "● RUNNING" : "Ⅱ PAUSED");
        }); pauseButton.setId("pause-simulation"); pauseButton.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> speed = new ComboBox<>(); speed.getItems().addAll("1×", "2×", "4×", "8×"); speed.setValue((int) playback + "×");
        speed.setOnAction(e -> playback = Integer.parseInt(speed.getValue().substring(0, 1)));
        speed.setPrefWidth(66); speed.setMinWidth(66); speed.getStyleClass().add("compact-combo");
        elapsedLabel = text(elapsed(), "mono", "tiny");
        Label scenarioLabel = text(scenario, "field-label"); scenarioLabel.setWrapText(true);
        VBox box = panel(text("SIMULATION CONTROLS", "eyebrow"), statusLabel,
            new VBox(5, text("CURRENT SCENARIO", "eyebrow"), scenarioLabel, text("SIM-007 / TRAINING", "eyebrow")),
            add, generate, pauseButton,
            row(6, text("Playback speed", "muted", "tiny"), spacer(), speed),
            row(6, text("Elapsed", "muted", "tiny"), spacer(), elapsedLabel));
        box.setSpacing(10); box.setPadding(new Insets(15)); return box;
    }

    private VBox trackedFlights() {
        trackCount = text("08", "eyebrow", "accent");
        flightRows = new VBox(3);
        ScrollPane scroll = new ScrollPane(flightRows); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(120); VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox list = panel(row(8, text("TRACKED FLIGHTS", "eyebrow"), spacer(), trackCount), scroll);
        list.setSpacing(10); list.setMinHeight(180); VBox.setVgrow(list, Priority.ALWAYS); return list;
    }

    private VBox radarPanel() {
        Label rangeLabel = text("RANGE " + range + " NM", "eyebrow");
        Button minus = button("−", "icon-button", () -> { range = Math.min(40, range + 5); radar.setRange(range); rangeLabel.setText("RANGE " + range + " NM"); });
        Button plus = button("＋", "icon-button", () -> { range = Math.max(10, range - 5); radar.setRange(range); rangeLabel.setText("RANGE " + range + " NM"); });
        minus.setTooltip(new Tooltip("Zoom out")); plus.setTooltip(new Tooltip("Zoom in"));
        Button fullscreen = button("⛶", "icon-button", () -> stage.setFullScreen(!stage.isFullScreen())); fullscreen.setTooltip(new Tooltip("Toggle full screen"));
        HBox toolbar = row(8, text("◎", "accent"), text("SECTOR 07 / LONDON UPPER", "eyebrow"), spacer(), text("NORTH UP", "eyebrow", "accent"), rangeLabel, minus, plus, fullscreen);
        toolbar.setPadding(new Insets(10, 12, 10, 12));
        HBox legend = row(16, text("◇ NORMAL TRACK", "eyebrow", "accent"), text("◇ CONFLICT", "eyebrow", "red-text"), text("┄ PROJECTED PATH", "eyebrow"), spacer());
        legend.setPadding(new Insets(14, 14, 14, 14));
        VBox container = new VBox(toolbar, radar, legend); container.getStyleClass().add("panel"); VBox.setVgrow(radar, Priority.ALWAYS);
        container.setMinWidth(380); return container;
    }

    private void refreshFlights() {
        if (flightRows == null || !loggedIn) return;
        flightRows.getChildren().clear();
        for (Flight flight : flights) {
            Label callsign = text(flight.callsign, "mono", "tiny", flight.conflict ? "red-text" : "");
            HBox item = row(4, callsign, spacer(), text(flight.flightLevel(), "eyebrow")); item.setPadding(new Insets(5, 8, 5, 8));
            item.getStyleClass().add("flight-row"); if (flight == selected) item.getStyleClass().add("selected");
            item.setOnMouseClicked(e -> selectFlight(flight)); flightRows.getChildren().add(item);
        }
        trackCount.setText(String.format("%02d", flights.size()));
        int pairs = conflictFlights().size() / 2;
        flightCount.setText(String.format("%02d ACTIVE FLIGHTS     %02d CONFLICT PAIRS", flights.size(), pairs));
        int active = (int) currentPairKeys().stream().filter(key -> !acknowledgedPairs.contains(key)).count();
        if (alertCount != null) { alertCount.setText(active + " ACTIVE ALERT" + (active == 1 ? "" : "S")); alertCount.getStyleClass().remove("red"); if (active > 0) alertCount.getStyleClass().add("red"); }
        renderWarnings(); renderDetails(); if (radar != null) radar.redraw();
    }

    private List<Flight> conflictFlights() { return flights.stream().filter(f -> f.conflict).toList(); }

    private Set<String> currentPairKeys() {
        List<Flight> conflicts = conflictFlights(); Set<String> keys = new HashSet<>();
        for (int i = 0; i + 1 < conflicts.size(); i += 2) keys.add(pairKey(conflicts.get(i), conflicts.get(i + 1)));
        return keys;
    }

    private String pairKey(Flight first, Flight second) { return first.callsign + "/" + second.callsign; }

    private void selectFlight(Flight flight) {
        selected = flight; if (radar != null) radar.setSelected(flight); refreshFlights();
    }

    private void renderWarnings() {
        warnings.getChildren().clear(); List<Flight> conflicts = conflictFlights();
        HBox title = row(8, text("△", "red-text"), text("Collision warnings", "section-title"), spacer(), text(String.format("%02d", conflicts.size() / 2), "eyebrow", "red-text"));
        title.setPadding(new Insets(16)); warnings.getChildren().add(title);
        if (conflicts.size() < 2) {
            Label empty = text("No conflict scenarios active.\nAll training tracks are clear.", "muted"); empty.setPadding(new Insets(0, 16, 20, 16)); warnings.getChildren().add(empty); return;
        }
        for (int i = 0; i + 1 < conflicts.size(); i += 2) {
            Flight a = conflicts.get(i), b = conflicts.get(i + 1);
            String pair = pairKey(a, b); boolean acknowledged = acknowledgedPairs.contains(pair);
            Button ack = button(acknowledged ? "✓  Acknowledged" : "Acknowledge →", "text-button", () -> { acknowledgedPairs.add(pair); refreshFlights(); notice("Conflict warning acknowledged"); });
            ack.setId("acknowledge-warning"); ack.setDisable(acknowledged);
            VBox card = new VBox(12,
                row(4, text("FLIGHT PAIR", "eyebrow"), spacer(), text("TIME TO CPA", "eyebrow")),
                row(8, new VBox(4, text(a.callsign, "mono"), text(b.callsign, "mono")), spacer(), text("01:08", "warning-time")),
                text("Predicted loss of separation\nCPA 0.8 NM / 400 ft vertical", "tiny"),
                row(4, text(acknowledged ? "● ACKNOWLEDGED" : "● CRITICAL", "eyebrow", acknowledged ? "accent" : "red-text"), spacer(), ack));
            card.getStyleClass().add("warning-card"); if (acknowledged) card.getStyleClass().add("acknowledged"); warnings.getChildren().add(card);
        }
        Label separation = text("MIN SEP  5.0 NM / 1,000 FT", "eyebrow"); separation.setPadding(new Insets(12, 16, 12, 16)); warnings.getChildren().add(separation);
    }

    private void renderDetails() {
        details.getChildren().clear();
        details.getChildren().add(row(6, text("Flight details", "section-title"), spacer(), text("SELECTED", "eyebrow", "accent")));
        if (selected == null) { details.getChildren().add(text("Select an aircraft on the radar.", "muted")); return; }
        Flight f = selected;
        HBox identity = row(6, text(f.callsign, "title", "mono"), spacer());
        if (f.conflict) identity.getChildren().add(text("● CONFLICT", "badge", "red"));
        HBox route = row(6, text(f.origin, "mono"), spacer(), text("→", "accent"), spacer(), text(f.destination, "mono")); route.getStyleClass().add("route");
        GridPane values = new GridPane(); values.setHgap(16); values.setVgap(18);
        for (int i = 0; i < 2; i++) { ColumnConstraints c = new ColumnConstraints(); c.setPercentWidth(50); values.getColumnConstraints().add(c); }
        values.add(detail("Altitude", String.format(Locale.US, "%,d ft", f.altitude)), 0, 0);
        values.add(detail("Groundspeed", f.speed + " kt"), 1, 0);
        values.add(detail("Heading", String.format("%03d°", f.heading)), 0, 1);
        values.add(detail("Vertical rate", "0 ft/min"), 1, 1);
        Button path = button(trajectory ? "⌁  Hide flight trajectory" : "⌁  View flight trajectory", "secondary-button", () -> { trajectory = !trajectory; radar.setTrajectoryVisible(trajectory); renderDetails(); });
        path.setMaxWidth(Double.MAX_VALUE); path.setId("view-trajectory");
        details.getChildren().addAll(identity, text(f.airline + " / " + f.type, "muted", "tiny"), route, values,
            row(6, text("Squawk / Flight rules", "muted", "tiny"), spacer(), text(f.squawk + " / IFR", "mono", "tiny")), path);
    }

    private VBox detail(String caption, String value) { return new VBox(7, text(caption, "muted", "tiny"), text(value, "detail-value")); }

    private void openAddFlight() {
        SimulationDialogs.addFlight(stage, flights, flight -> {
            flights.add(flight); selectFlight(flight); notice(flight.callsign + " added to simulation");
        }, picker -> {
            notice("Click a position on the radar for the new aircraft");
            radar.setPicking(picker);
        });
    }

    private void showAdmin() {
        radar = null; flightRows = null; warnings = null; details = null;
        shell.setTop(header("Administration"));
        if (adminView == null) adminView = new AdminView(this::showRadar, this::notice);
        shell.setCenter(adminView);
    }

    private void notice(String message) {
        Label toast = text(message, "toast"); toast.setMouseTransparent(true);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER); StackPane.setMargin(toast, new Insets(0, 0, 50, 0));
        root.getChildren().add(toast);
        PauseTransition hide = new PauseTransition(Duration.seconds(4)); hide.setOnFinished(e -> root.getChildren().remove(toast)); hide.play();
    }

    private void tick() {
        if (utcClock != null) utcClock.setText(utc());
        if (!loggedIn || !running) return;
        elapsed += playback; sweep = (sweep + 5 * playback) % 360;
        for (Flight f : flights) {
            double distance = f.speed / 450.0 * .00055 * playback;
            f.x += Math.sin(Math.toRadians(f.heading)) * distance; f.y -= Math.cos(Math.toRadians(f.heading)) * distance;
            if (f.x > .97) f.x = .03; if (f.x < .03) f.x = .97; if (f.y > .97) f.y = .03; if (f.y < .03) f.y = .97;
        }
        if (radar != null) { radar.setSweep(sweep); elapsedLabel.setText(elapsed()); }
    }

    private String elapsed() { int t = (int) elapsed; return String.format("%02d:%02d:%02d", t / 3600, t / 60 % 60, t % 60); }
    private String utc() { return DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(Instant.now()); }

    private void saveScreenshot(String filename) {
        try {
            root.applyCss(); root.layout();
            WritableImage image = root.snapshot(new SnapshotParameters(), null);
            BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            File file = new File(filename); if (file.getParentFile() != null) file.getParentFile().mkdirs();
            ImageIO.write(output, "png", file);
            System.out.println("Screenshot saved: " + file.getAbsolutePath());
        } catch (Exception ex) { ex.printStackTrace(); throw new IllegalStateException("Could not save screenshot", ex); }
    }
}
