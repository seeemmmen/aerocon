package pl.aerocon.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class Ui {
    private Ui() {}

    public static Label text(String value, String... styles) {
        Label label = new Label(value);
        label.getStyleClass().addAll(styles);
        return label;
    }

    public static Button button(String value, String style, Runnable action) {
        Button button = new Button(value);
        button.getStyleClass().add(style);
        button.setOnAction(e -> action.run());
        return button;
    }

    public static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    public static HBox row(double gap, Node... children) {
        HBox box = new HBox(gap, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static VBox field(String caption, Node control) {
        VBox box = new VBox(8, text(caption, "field-label"), control);
        if (control instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    public static TextField input(String value, String prompt) {
        TextField field = new TextField(value);
        field.setPromptText(prompt);
        field.getStyleClass().add("field");
        return field;
    }

    public static VBox panel(Node... children) {
        VBox box = new VBox(16, children);
        box.setPadding(new Insets(18));
        box.getStyleClass().add("panel");
        return box;
    }
}
