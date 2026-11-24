package pl.aerocon;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Standalone integration smoke test. Interacts with the actual JavaFX controls. */
public final class PrototypeSmokeTest extends Application {
    private Stage stage;
    private AeroconApplication app;
    private int checks;

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        this.stage = stage;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
        try {
            app = new AeroconApplication();
            app.start(stage);
            Platform.runLater(() -> safely(() -> await(this::loginReady, this::testLoginAndPause)));
        } catch (Throwable error) { fail(error); }
    }

    private void testLoginAndPause() {
        smallSnapshot("login-small.png", this::testLogin);
    }

    private void testLogin() {
        TextField username = lookup(root(), "#username-field", TextField.class);
        PasswordField password = lookup(root(), "#password-field", PasswordField.class);
        username.setText("unknown"); password.setText("wrong");
        click("login-button");
        await(() -> loginReady() && invalidCredentials(), () -> {
            check(root().lookup("#login-button") != null && invalidCredentials(), "invalid login stays on login screen and rejects credentials");
            username.setText("maya.chen"); password.setText("aerocon");
            select(lookup(root(), "#role-select", ComboBox.class), "Admin");
            click("login-button");
            await(() -> loginReady() && invalidCredentials(), () -> {
                check(invalidCredentials(), "controller credentials cannot enter the admin role");
                select(lookup(root(), "#role-select", ComboBox.class), "Controller");
                loginController(username, password);
            });
        });
    }

    private void loginController(TextField username, PasswordField password) {
        username.setText("maya.chen"); password.setText("aerocon");
        click("login-button");
        await(() -> root().lookup("#add-flight") != null, () -> {
            check(rowCount() == 8, "controller login opens eight demo tracks");
            smallSnapshot("radar-small.png", this::testPause);
        });
    }

    private void testPause() {
        click("pause-simulation");
        check(button("pause-simulation").getText().contains("Resume") && label(root(), s -> s.contains("PAUSED")), "pause exposes paused status and resume action");
        String pausedTime = elapsed();
        later(1250, () -> {
            check(elapsed().equals(pausedTime), "simulation clock remains still while paused");
            click("pause-simulation");
            later(1250, () -> {
                check(!elapsed().equals(pausedTime), "simulation clock advances after resume");
                click("pause-simulation");
                testFlightForms();
                testGeneratorAndAlerts();
            });
        });
    }

    private void testFlightForms() {
        click("view-trajectory");
        check(button("view-trajectory").getText().contains("Hide"), "trajectory toggles on");
        click("view-trajectory");
        check(button("view-trajectory").getText().contains("View"), "trajectory toggles off");

        click("add-flight");
        DialogPane add = dialog();
        lookup(add, "#callsign-field", TextField.class).setText("LOT281");
        snapshot(add, "add-flight.png");
        lookup(add, "#callsign-field", TextField.class).clear();
        fire(add, "confirm-flight");
        check(dialogShowing() && label(add, s -> s.contains("Use a callsign")) && rowCount() == 8, "blank callsign is rejected without adding a track");
        lookup(add, "#callsign-field", TextField.class).setText("LOT281");
        rejectField(add, "Origin ICAO", "BAD", "EGLL", "ICAO");
        rejectField(add, "Initial altitude · FT", "999", "34000", "Altitude");
        rejectField(add, "Groundspeed · KT", "99", "450", "Groundspeed");
        rejectField(add, "Heading · DEG", "360", "090", "Heading");
        rejectField(add, "Squawk code", "8888", "4521", "Squawk");
        rejectField(add, "Latitude", "NaN", "51.4700", "Latitude");
        rejectField(add, "Longitude", "10", "-0.4543", "outside this training sector");
        fire(add, "confirm-flight");
        check(!dialogShowing() && rowCount() == 9 && label(root(), s -> s.equals("LOT281")), "valid flight adds a visible selected track");

        click("add-flight"); add = dialog();
        lookup(add, "#callsign-field", TextField.class).setText("LOT281");
        fire(add, "confirm-flight");
        check(dialogShowing() && rowCount() == 9 && label(add, s -> s.contains("already being tracked")), "duplicate callsign is rejected");
        ((Button) add.lookupButton(ButtonType.CANCEL)).fire();
        check(!dialogShowing() && rowCount() == 9, "cancel closes the form without adding a track");
    }

    private void rejectField(DialogPane pane, String caption, String invalid, String valid, String message) {
        TextField input = field(pane, caption);
        input.setText(invalid); fire(pane, "confirm-flight");
        check(dialogShowing() && rowCount() == 8 && label(pane, s -> s.contains(message)), caption + " validation rejects " + invalid);
        input.setText(valid);
    }

    private void testGeneratorAndAlerts() {
        click("generate-traffic");
        DialogPane pane = dialog();
        snapshot(pane, "generate.png");
        ComboBox<?> preset = all(pane, ComboBox.class).getFirst();
        select(preset, "High Density");
        check(slider(pane).getValue() == 30, "high density preset selects 30 aircraft");
        select(preset, "Conflict Stress Test");
        check(checkBox(pane, "Inject a deliberate collision conflict").isSelected(), "conflict preset enables conflict injection");
        slider(pane).setValue(1);
        fire(pane, "confirm-generate");
        check(dialogShowing() && rowCount() == 9 && label(pane, s -> s.contains("At least two")), "one-aircraft conflict scenario is rejected");
        ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
        check(rowCount() == 9 && !dialogShowing(), "cancel generator preserves the existing traffic");

        generate(4, true, true);
        check(rowCount() == 4 && alertButtons().size() == 1, "clear and generate replaces traffic with four tracks and one conflict pair");
        generate(3, false, true);
        check(rowCount() == 7 && alertButtons().size() == 2 && label(root(), s -> s.equals("2 ACTIVE ALERTS")), "append adds exactly three tracks and a second conflict pair");
        await(() -> alertButtons().size() == 2 && alertButtons().stream().noneMatch(Button::isDisabled), this::acknowledgeFirstPair);
    }

    private void acknowledgeFirstPair() {
        alertButtons().getFirst().fire();
        await(() -> alertButtons().stream().filter(Button::isDisabled).count() == 1 && label(root(), s -> s.equals("1 ACTIVE ALERT")), () -> {
            check(label(root(), s -> s.equals("1 ACTIVE ALERT")), "acknowledging one pair leaves the other warning active");
            alertButtons().stream().filter(b -> !b.isDisabled()).findFirst().orElseThrow().fire();
            await(() -> alertButtons().stream().allMatch(Button::isDisabled) && label(root(), s -> s.equals("0 ACTIVE ALERTS")), () -> {
                check(label(root(), s -> s.equals("0 ACTIVE ALERTS")), "second pair can be acknowledged independently");
                testAcknowledgementPersistence();
            });
        });
    }

    private void testAcknowledgementPersistence() {
        generate(2, false, false);
        check(rowCount() == 9 && alertButtons().stream().allMatch(Button::isDisabled) && label(root(), s -> s.equals("0 ACTIVE ALERTS")), "append preserves acknowledgement for existing pairs");
        generate(2, true, false);
        check(rowCount() == 2 && alertButtons().isEmpty() && label(root(), s -> s.contains("No conflict scenarios active")), "clear and normal generation removes conflict warnings");
        testAdminLogin();
    }

    private void generate(int count, boolean clear, boolean conflict) {
        click("generate-traffic");
        DialogPane pane = dialog();
        slider(pane).setValue(count);
        checkBox(pane, "Clear existing flights before generating").setSelected(clear);
        checkBox(pane, "Inject a deliberate collision conflict").setSelected(conflict);
        fire(pane, "confirm-generate");
        if (dialogShowing()) throw new AssertionError("Generator did not close after a valid submission");
    }

    private void testAdminLogin() {
        MenuButton account = all(root(), MenuButton.class).stream()
                .filter(b -> b.getItems().stream().anyMatch(i -> i.getText().equals("Log out"))).findFirst().orElseThrow();
        account.getItems().stream().filter(i -> i.getText().equals("Log out")).findFirst().orElseThrow().fire();
        await(this::loginReady, this::submitAdminLogin);
    }

    private void submitAdminLogin() {
        check(root().lookup("#login-button") != null, "logout returns to the login form");
        lookup(root(), "#username-field", TextField.class).setText("sarah.patel");
        lookup(root(), "#password-field", PasswordField.class).setText("aerocon");
        select(lookup(root(), "#role-select", ComboBox.class), "Admin");
        click("login-button");
        await(() -> label(root(), s -> s.equals("User & incident administration")) && all(root(), TableView.class).stream().anyMatch(t -> t.getItems().size() == 6), this::testAdminSearch);
    }

    private void testAdminSearch() {
        check(label(root(), s -> s.equals("User & incident administration")), "admin login opens the administration workspace");
        TextField search = all(root(), TextField.class).stream().filter(f -> "Search name or email".equals(f.getPromptText())).findFirst().orElseThrow();
        search.setText("Elena");
        check(all(root(), TableView.class).stream().anyMatch(t -> t.getItems().size() == 1), "admin search filters to a matching operator");
        search.clear();
        check(all(root(), TableView.class).stream().anyMatch(t -> t.getItems().size() == 6), "clearing admin search restores six operators");
        all(root(), Button.class).stream().filter(b -> b.getText().contains("Incident Logs")).findFirst().orElseThrow().fire();
        check(label(root(), s -> s.contains("INCIDENTS")), "admin can open incident history");
        System.out.println("SMOKE TEST PASSED: " + checks + " checks");
        app.stop();
        Platform.exit();
    }

    private Parent root() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); return stage.getScene().getRoot(); }
    private int rowCount() { return root().lookupAll(".flight-row").size(); }
    private Button button(String id) { return lookup(root(), "#" + id, Button.class); }
    private void click(String id) { button(id).fire(); root(); }
    private static void fire(Parent parent, String id) { lookup(parent, "#" + id, Button.class).fire(); }
    private static <T extends Node> T lookup(Parent parent, String selector, Class<T> type) {
        parent.applyCss(); parent.layout(); Node node = parent.lookup(selector);
        if (!type.isInstance(node)) throw new AssertionError("Missing " + selector);
        return type.cast(node);
    }
    private DialogPane dialog() {
        return Window.getWindows().stream().filter(w -> w != stage && w.isShowing())
                .map(w -> w.getScene().getRoot()).filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast).findFirst().orElseThrow(() -> new AssertionError("No open dialog"));
    }
    private boolean dialogShowing() { return Window.getWindows().stream().anyMatch(w -> w != stage && w.isShowing() && w.getScene().getRoot() instanceof DialogPane); }
    private List<Button> alertButtons() { return all(root(), Button.class).stream().filter(b -> "acknowledge-warning".equals(b.getId())).toList(); }
    private String elapsed() { return all(root(), Label.class).stream().map(Label::getText).filter(s -> s.matches("\\d{2}:\\d{2}:\\d{2}")).findFirst().orElseThrow(); }
    private static boolean label(Parent parent, Predicate<String> predicate) { return all(parent, Label.class).stream().anyMatch(l -> predicate.test(l.getText())); }
    private static TextField field(Parent parent, String caption) {
        return all(parent, VBox.class).stream().filter(box -> !box.getChildren().isEmpty() && box.getChildren().getFirst() instanceof Label l && caption.equals(l.getText()))
                .map(box -> all(box, TextField.class)).filter(list -> !list.isEmpty()).findFirst().orElseThrow().getFirst();
    }
    private static Slider slider(Parent pane) { return all(pane, Slider.class).getFirst(); }
    private static CheckBox checkBox(Parent pane, String caption) { return all(pane, CheckBox.class).stream().filter(c -> c.getText().equals(caption)).findFirst().orElseThrow(); }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void select(ComboBox box, String value) { box.setValue(value); }
    private static <T extends Node> List<T> all(Parent root, Class<T> type) {
        root.applyCss(); root.layout(); List<T> found = new ArrayList<>();
        walk(root, node -> { if (type.isInstance(node)) found.add(type.cast(node)); }); return found;
    }
    private static void walk(Node node, Consumer<Node> visitor) { visitor.accept(node); if (node instanceof Parent p) for (Node child : p.getChildrenUnmodifiable()) walk(child, visitor); }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; System.out.println("PASS " + checks + ": " + message); }
    private void later(double milliseconds, Runnable action) { PauseTransition wait = new PauseTransition(Duration.millis(milliseconds)); wait.setOnFinished(e -> safely(action)); wait.play(); }
    private void safely(Runnable action) { try { action.run(); } catch (Throwable error) { fail(error); } }
    private boolean loginReady() { Node login = root().lookup("#login-button"); return login != null && !login.isDisabled(); }
    private boolean invalidCredentials() { return label(root(), s -> s.equals("Invalid credentials or role, or account is disabled.")); }
    private void await(BooleanSupplier condition, Runnable next) { await(condition, next, System.nanoTime() + 20_000_000_000L); }
    private void await(BooleanSupplier condition, Runnable next, long deadline) {
        if (condition.getAsBoolean()) { next.run(); return; }
        if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for the asynchronous JavaFX operation");
        later(40, () -> await(condition, next, deadline));
    }
    private void fail(Throwable error) { error.printStackTrace(); if (app != null) app.stop(); Platform.exit(); System.exit(1); }
    private void smallSnapshot(String name, Runnable next) {
        double width = stage.getWidth(), height = stage.getHeight();
        stage.setWidth(1120); stage.setHeight(740);
        later(160, () -> {
            snapshot(root(), name);
            stage.setWidth(width); stage.setHeight(height);
            later(160, next);
        });
    }
    private static void snapshot(Parent pane, String name) {
        try {
            pane.applyCss(); pane.layout(); WritableImage image = pane.snapshot(null, null);
            BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            File file = new File("tmp/previews/" + name); file.getParentFile().mkdirs(); ImageIO.write(output, "png", file);
        } catch (Exception error) { throw new IllegalStateException("Could not save dialog preview", error); }
    }
}
