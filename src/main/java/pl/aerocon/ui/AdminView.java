package pl.aerocon.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** In-memory operator management and incident history for the training prototype. */
public final class AdminView extends VBox {
    private final ObservableList<Operator> operators = FXCollections.observableArrayList(
            new Operator("Maya Chen", "maya.chen@aerocon.atc", "Controller", true, "22 Nov 2026 · 14:32 UTC", "Sector 07 / London Upper"),
            new Operator("Sarah Patel", "sarah.patel@aerocon.atc", "Admin", true, "22 Nov 2026 · 14:31 UTC", "All sectors"),
            new Operator("James Wilson", "james.wilson@aerocon.atc", "Controller", true, "22 Nov 2026 · 13:50 UTC", "Sector 07 / London Upper"),
            new Operator("Elena Rossi", "elena.rossi@aerocon.atc", "Controller", true, "22 Nov 2026 · 13:42 UTC", "Sector 07 / London Upper"),
            new Operator("Noah Brooks", "noah.brooks@aerocon.atc", "Admin", true, "22 Nov 2026 · 12:10 UTC", "All sectors"),
            new Operator("Oliver Grant", "oliver.grant@aerocon.atc", "Controller", false, "21 Nov 2026 · 18:05 UTC", "Sector 04 / London Lower")
    );
    private final ObservableList<Incident> incidents = FXCollections.observableArrayList(
            new Incident("INC-0041", LocalDate.of(2026, 11, 22), "14:32:00", "BAW452 / AAL217", "Critical", "Predicted separation 0.8 NM / 400 ft", "Open", "Sector 07"),
            new Incident("INC-0040", LocalDate.of(2026, 11, 22), "14:24:16", "DLH908 / UAE031", "Critical", "Separation restored to 6.2 NM / 2,000 ft", "Resolved", "Sector 07"),
            new Incident("INC-0039", LocalDate.of(2026, 11, 22), "14:18:42", "AFR168 / KLM602", "Critical", "Separation restored to 8.1 NM / 4,000 ft", "Resolved", "Sector 07")
    );
    private final FilteredList<Operator> filteredOperators = new FilteredList<>(operators, item -> true);
    private final FilteredList<Incident> filteredIncidents = new FilteredList<>(incidents, item -> true);
    private final Consumer<String> onNotice;
    private final Label registeredCount = text("06", "mono");
    private final Label enabledCount = text("05", "mono");
    private final Label registeredBreakdown = text("4 controllers / 2 admins", "muted");
    private final Label enabledBreakdown = text("1 account disabled", "muted");
    private final Label userSummary = text("", "eyebrow");
    private final Label incidentSummary = text("03 INCIDENTS", "eyebrow");
    private final VBox userPanel;
    private final VBox incidentPanel;
    private final ScrollPane scroll;
    private final Button usersTab;
    private final Button incidentsTab;

    public AdminView(Runnable onRadar, Consumer<String> onNotice) {
        this.onNotice = onNotice;
        setSpacing(0);
        setMinHeight(0);
        HBox tabs = new HBox(10);
        tabs.setAlignment(Pos.CENTER_LEFT);
        tabs.setPadding(new Insets(0, 24, 0, 24));
        tabs.setStyle("-fx-background-color: #0b1729; -fx-border-color: transparent transparent #223148 transparent;");
        Button radarTab = button("◎  Radar View", "tab-button", onRadar);
        usersTab = button("♙  User Management", "tab-button", () -> selectTab(false));
        incidentsTab = button("▤  Incident Logs", "tab-button", () -> selectTab(true));
        Region tabSpacer = new Region();
        HBox.setHgrow(tabSpacer, Priority.ALWAYS);
        tabs.getChildren().addAll(radarTab, usersTab, incidentsTab, tabSpacer, text("ACCESS LEVEL / ADMINISTRATOR", "eyebrow"));

        VBox content = new VBox(12);
        content.setPadding(new Insets(16, 24, 16, 24));
        HBox heading = new HBox(12);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox titles = new VBox(4, text("User & incident administration", "title"),
                text("Manage operator access and review the airspace safety audit trail.", "muted"));
        Region headingSpacer = new Region();
        HBox.setHgrow(headingSpacer, Priority.ALWAYS);
        heading.getChildren().addAll(titles, headingSpacer, text("TRAINING WORKSPACE / UTC", "eyebrow"));

        HBox stats = new HBox(12);
        stats.getChildren().addAll(stat(registeredCount, "Registered users", registeredBreakdown),
                stat(enabledCount, "Enabled accounts", enabledBreakdown),
                stat(text("01", "mono"), "Open incidents", text("BAW452 / AAL217", "muted")),
                stat(text("Online", "mono"), "Audit status", text("Local training session", "muted")));
        ((Label) ((HBox) stats.getChildren().get(2)).getChildren().get(0)).setStyle("-fx-text-fill: #ed2863; -fx-font-size: 25;");

        userPanel = createUsersPanel();
        incidentPanel = createIncidentPanel();
        content.getChildren().addAll(heading, stats, userPanel, incidentPanel);
        scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: #0b1226; -fx-border-width: 0;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(tabs, scroll);
        operators.addListener((javafx.collections.ListChangeListener<Operator>) change -> updateCounts());
        updateCounts();
        selectTab(false);
    }

    private HBox stat(Label value, String title, Label subtitle) {
        value.setStyle("-fx-text-fill: #00dbc1; -fx-font-size: 25;");
        VBox labels = new VBox(4, text(title, ""), subtitle);
        labels.setMinWidth(0);
        HBox.setHgrow(labels, Priority.ALWAYS);
        HBox box = new HBox(14, value, labels);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(11, 14, 11, 14));
        box.getStyleClass().add("panel");
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox createUsersPanel() {
        VBox panel = new VBox(0);
        panel.getStyleClass().add("panel");
        HBox toolbar = new HBox(10);
        toolbar.setPadding(new Insets(8, 14, 8, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Label title = text("Registered users", "");
        title.setStyle("-fx-font-weight: bold;");
        TextField search = new TextField();
        search.setPromptText("Search name or email");
        search.setPrefWidth(210);
        search.getStyleClass().add("field");
        search.setStyle("-fx-padding: 6 10; -fx-font-size: 12;");
        ComboBox<String> role = new ComboBox<>(FXCollections.observableArrayList("All roles", "Controller", "Admin"));
        role.getSelectionModel().selectFirst();
        role.setPrefWidth(135);
        role.setStyle("-fx-padding: 0 4; -fx-font-size: 12;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolbar.getChildren().addAll(title, userSummary, spacer, search, role,
                compactButton("+  Add User", "primary-button", () -> showOperatorForm(null)));
        Runnable applyFilter = () -> {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            filteredOperators.setPredicate(user -> (query.isEmpty() || user.name().toLowerCase(Locale.ROOT).contains(query)
                    || user.email().toLowerCase(Locale.ROOT).contains(query))
                    && ("All roles".equals(role.getValue()) || user.role().equals(role.getValue())));
            updateCounts();
        };
        search.textProperty().addListener((obs, old, value) -> applyFilter.run());
        role.valueProperty().addListener((obs, old, value) -> applyFilter.run());

        TableView<Operator> table = new TableView<>(filteredOperators);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(32);
        table.setPrefHeight(232);
        table.setMinHeight(232);
        table.setMaxHeight(232);
        table.setPlaceholder(text("No operators match these filters.", "muted"));
        TableColumn<Operator, String> name = column("USER", 155, Operator::name);
        TableColumn<Operator, String> email = column("EMAIL", 245, Operator::email);
        TableColumn<Operator, String> roleColumn = column("ROLE", 130, Operator::role);
        roleColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(null);
                if (empty || value == null) { setGraphic(null); return; }
                Label badge = text("▪ " + value.toUpperCase(Locale.ROOT), "eyebrow");
                badge.setStyle("-fx-text-fill: " + (value.equals("Admin") ? "#00dbc1" : "#a4b4c9")
                        + "; -fx-background-color: " + (value.equals("Admin") ? "#073d40" : "#1a2940")
                        + "; -fx-padding: 3 7; -fx-background-radius: 3;");
                setGraphic(badge);
            }
        });
        TableColumn<Operator, String> status = column("ACCESS STATUS", 145, user -> user.enabled() ? "▪ Enabled" : "▪ Disabled");
        status.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                setStyle("-fx-text-fill: " + (value != null && value.endsWith("Enabled") ? "#00dbc1" : "#8291a8") + ";");
            }
        });
        TableColumn<Operator, String> lastActive = column("LAST ACTIVE", 225, Operator::lastActive);
        TableColumn<Operator, String> actions = column("ACTIONS", 148, Operator::name);
        actions.setMinWidth(143);
        actions.setMaxWidth(155);
        actions.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(null);
                if (empty) { setGraphic(null); return; }
                Button edit = button("Edit", "secondary-button", () -> showOperatorForm(getTableRow().getItem()));
                Button delete = button("Delete", "danger-button", () -> showDeleteForm(getTableRow().getItem()));
                edit.setStyle("-fx-padding: 3 8; -fx-font-size: 12;");
                delete.setStyle("-fx-padding: 3 8; -fx-font-size: 12;");
                setPadding(new Insets(2, 10, 2, 10));
                HBox box = new HBox(7, edit, delete);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        table.getColumns().addAll(List.of(name, email, roleColumn, status, lastActive, actions));
        Label footer = text("Role changes apply at the next login.   ·   Training data is stored in memory for this session.", "eyebrow");
        footer.setPadding(new Insets(7, 14, 7, 14));
        panel.getChildren().addAll(toolbar, table, footer);
        return panel;
    }

    private VBox createIncidentPanel() {
        VBox panel = new VBox(0);
        panel.getStyleClass().add("panel");
        HBox toolbar = new HBox(10);
        toolbar.setPadding(new Insets(8, 14, 3, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Label title = text("Incident history", "");
        title.setStyle("-fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolbar.getChildren().addAll(title, text("SECTOR 07 / SIM-007", "eyebrow"), spacer,
                compactButton("↓  Export CSV", "secondary-button", this::exportIncidents));
        HBox filters = new HBox(9);
        filters.setPadding(new Insets(4, 14, 8, 14));
        filters.setAlignment(Pos.CENTER_LEFT);
        DatePicker date = new DatePicker();
        date.setPromptText("All dates");
        date.setPrefWidth(145);
        date.setEditable(false);
        date.setStyle("-fx-padding: 0; -fx-font-size: 12;");
        date.getEditor().setStyle("-fx-padding: 6 9; -fx-font-size: 12;");
        ComboBox<String> severity = choices("Severity: All", "Critical", "Warning");
        ComboBox<String> status = choices("Status: All", "Open", "Resolved");
        ComboBox<String> sector = choices("All sectors", "Sector 07", "Sector 04");
        for (ComboBox<String> filter : List.of(severity, status, sector)) {
            filter.setStyle("-fx-padding: 0 4; -fx-font-size: 12;");
        }
        Runnable applyFilter = () -> {
            filteredIncidents.setPredicate(incident -> (date.getValue() == null || incident.date().equals(date.getValue()))
                    && (severity.getSelectionModel().getSelectedIndex() == 0 || incident.severity().equals(severity.getValue()))
                    && (status.getSelectionModel().getSelectedIndex() == 0 || incident.status().equals(status.getValue()))
                    && (sector.getSelectionModel().getSelectedIndex() == 0 || incident.sector().equals(sector.getValue())));
            incidentSummary.setText(String.format("%02d INCIDENTS", filteredIncidents.size()));
        };
        date.valueProperty().addListener((obs, old, value) -> applyFilter.run());
        severity.valueProperty().addListener((obs, old, value) -> applyFilter.run());
        status.valueProperty().addListener((obs, old, value) -> applyFilter.run());
        sector.valueProperty().addListener((obs, old, value) -> applyFilter.run());
        Button clear = compactButton("Clear filters", "secondary-button", () -> {
            date.setValue(null);
            severity.getSelectionModel().selectFirst();
            status.getSelectionModel().selectFirst();
            sector.getSelectionModel().selectFirst();
        });
        Region filterSpacer = new Region();
        HBox.setHgrow(filterSpacer, Priority.ALWAYS);
        filters.getChildren().addAll(date, severity, status, sector, clear, filterSpacer, incidentSummary);

        TableView<Incident> table = new TableView<>(filteredIncidents);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(32);
        table.setPrefHeight(134);
        table.setMinHeight(134);
        table.setMaxHeight(134);
        table.setPlaceholder(text("No incidents match these filters.", "muted"));
        table.getColumns().addAll(List.of(
                column("INCIDENT ID", 125, Incident::id), column("TIME / UTC", 120, Incident::time),
                column("FLIGHT PAIR", 190, Incident::flights), column("SEVERITY", 110, Incident::severity),
                column("EVENT DETAIL", 365, Incident::detail), column("STATUS", 125, Incident::status)));
        table.setRowFactory(view -> new TableRow<>() {
            @Override protected void updateItem(Incident item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && item.status().equals("Open")
                        ? "-fx-background-color: #301a31; -fx-text-background-color: #ed6190;" : "");
            }
        });
        Label footer = text("IMMUTABLE AUDIT TRAIL   /   RETENTION 90 DAYS   /   ALL TIMES UTC", "eyebrow");
        footer.setPadding(new Insets(7, 14, 7, 14));
        panel.getChildren().addAll(toolbar, filters, table, footer);
        return panel;
    }

    private void selectTab(boolean incidentsOnly) {
        userPanel.setVisible(!incidentsOnly);
        userPanel.setManaged(!incidentsOnly);
        usersTab.getStyleClass().remove("active");
        incidentsTab.getStyleClass().remove("active");
        (incidentsOnly ? incidentsTab : usersTab).getStyleClass().add("active");
        if (scroll != null) scroll.setVvalue(0);
    }

    private void updateCounts() {
        long controllers = operators.stream().filter(user -> user.role().equals("Controller")).count();
        long admins = operators.size() - controllers;
        long enabled = operators.stream().filter(Operator::enabled).count();
        long disabled = operators.size() - enabled;
        registeredCount.setText(String.format("%02d", operators.size()));
        enabledCount.setText(String.format("%02d", enabled));
        registeredBreakdown.setText(controllers + " controllers / " + admins + " admins");
        enabledBreakdown.setText(disabled + (disabled == 1 ? " account disabled" : " accounts disabled"));
        userSummary.setText(String.format("%02d OF %02d TOTAL", filteredOperators.size(), operators.size()));
    }

    private void showOperatorForm(Operator existing) {
        boolean edit = existing != null;
        Dialog<ButtonType> dialog = createDialog(edit ? "Edit User" : "Add New Operator");
        TextField name = field(existing == null ? "" : existing.name(), "Full name");
        TextField email = field(existing == null ? "" : existing.email(), "operator@aerocon.atc");
        ComboBox<String> role = choices("Controller", "Admin");
        role.setMaxWidth(Double.MAX_VALUE);
        if (edit) role.setValue(existing.role());
        TextField clearance = field(existing == null ? "Sector 07 / London Upper" : existing.clearance(), "Access clearance / sector");
        CheckBox enabled = new CheckBox("Account enabled");
        enabled.setSelected(existing == null || existing.enabled());
        Label error = text("", "");
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #ed6190;");
        VBox form = new VBox(10, text(edit ? "EDIT USER" : "ADD NEW OPERATOR", "eyebrow"),
                text(edit ? "Update operator access" : "Create an operator account", "title"),
                text("Full name", "muted"), name, text("Email address", "muted"), email,
                text("Role selection", "muted"), role, text("Access clearance / sector", "muted"), clearance, enabled, error);
        form.setPadding(new Insets(12, 8, 8, 8));
        form.setPrefWidth(410);
        dialog.getDialogPane().setContent(form);
        ButtonType confirm = new ButtonType(edit ? "Confirm Changes" : "Create Account", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        styleDialogButtons(dialog, confirm, false);
        Node confirmButton = dialog.getDialogPane().lookupButton(confirm);
        confirmButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (name.getText().trim().isEmpty()) {
                error.setText("Enter the operator's full name.");
                event.consume();
            } else if (!email.getText().trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                error.setText("Enter a valid email address.");
                event.consume();
            } else if (operators.stream().anyMatch(user -> user != existing && user.email().equalsIgnoreCase(email.getText().trim()))) {
                error.setText("An operator with this email address already exists.");
                event.consume();
            } else if (clearance.getText().trim().isEmpty()) {
                error.setText("Enter an access clearance or sector.");
                event.consume();
            }
        });
        dialog.showAndWait().filter(result -> result == confirm).ifPresent(result -> {
            Operator updated = new Operator(name.getText().trim(), email.getText().trim(), role.getValue(), enabled.isSelected(),
                    edit ? existing.lastActive() : "Never signed in", clearance.getText().trim());
            if (edit) operators.set(operators.indexOf(existing), updated); else operators.add(updated);
            onNotice.accept(edit ? "Operator account updated." : "Operator account created for this training session.");
        });
    }

    private void showDeleteForm(Operator operator) {
        if (operator == null) return;
        Dialog<ButtonType> dialog = createDialog("Delete User");
        Label warning = text("Remove this operator from the training workspace? This action cannot be undone during this session.", "muted");
        warning.setWrapText(true);
        VBox content = new VBox(14, text("DELETE USER", "eyebrow"), text(operator.name(), "title"),
                text(operator.email(), "mono"), warning);
        content.setPadding(new Insets(12, 8, 16, 8));
        content.setPrefWidth(410);
        dialog.getDialogPane().setContent(content);
        ButtonType delete = new ButtonType("Delete User", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, delete);
        styleDialogButtons(dialog, delete, true);
        dialog.showAndWait().filter(result -> result == delete).ifPresent(result -> {
            operators.remove(operator);
            onNotice.accept("Operator removed from this training session.");
        });
    }

    private Dialog<ButtonType> createDialog(String title) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        var stylesheet = getClass().getResource("/pl/aerocon/aerocon.css");
        if (stylesheet != null) dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
        dialog.getDialogPane().setStyle("-fx-background-color: #101c32; -fx-border-color: #223148; -fx-padding: 14;");
        return dialog;
    }

    private void styleDialogButtons(Dialog<ButtonType> dialog, ButtonType action, boolean destructive) {
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).getStyleClass().add("secondary-button");
        dialog.getDialogPane().lookupButton(action).getStyleClass().add(destructive ? "danger-button" : "primary-button");
    }

    private void exportIncidents() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export incident history");
        chooser.setInitialFileName("aerocon-incidents.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        StringBuilder csv = new StringBuilder("Incident ID,Date,Time UTC,Flight pair,Severity,Event detail,Status,Sector\r\n");
        for (Incident incident : filteredIncidents) {
            csv.append(String.join(",", quote(incident.id()), quote(incident.date().toString()), quote(incident.time()),
                    quote(incident.flights()), quote(incident.severity()), quote(incident.detail()), quote(incident.status()), quote(incident.sector())))
                    .append("\r\n");
        }
        try {
            Files.writeString(file.toPath(), csv, StandardCharsets.UTF_8);
            onNotice.accept("Exported " + filteredIncidents.size() + " incidents to " + file.getName() + ".");
        } catch (IOException exception) {
            onNotice.accept("Could not save the CSV file: " + exception.getMessage());
        }
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    private static Label text(String value, String style) {
        Label label = new Label(value);
        if (!style.isBlank()) label.getStyleClass().add(style);
        return label;
    }

    private static Button button(String value, String style, Runnable action) {
        Button button = new Button(value);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Button compactButton(String value, String style, Runnable action) {
        Button button = button(value, style, action);
        button.setStyle("-fx-padding: 6 11; -fx-font-size: 12;");
        return button;
    }

    private static ComboBox<String> choices(String... values) {
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(values));
        box.getSelectionModel().selectFirst();
        return box;
    }

    private static TextField field(String value, String prompt) {
        TextField field = new TextField(value);
        field.setPromptText(prompt);
        field.getStyleClass().add("field");
        return field;
    }

    private static <T> TableColumn<T, String> column(String title, double width, java.util.function.Function<T, String> getter) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return column;
    }

    private record Operator(String name, String email, String role, boolean enabled, String lastActive, String clearance) { }
    private record Incident(String id, LocalDate date, String time, String flights, String severity, String detail, String status, String sector) { }
}
