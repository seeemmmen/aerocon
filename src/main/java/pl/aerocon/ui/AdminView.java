package pl.aerocon.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import pl.aerocon.data.AeroconRepository;
import pl.aerocon.model.Incident;
import pl.aerocon.model.Operator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Database-backed operator management and incident history. All I/O runs off the FX thread. */
public final class AdminView extends VBox {
    private final AeroconRepository repository;
    private final Operator currentUser;
    private final ObservableList<Operator> operators = FXCollections.observableArrayList();
    private final ObservableList<Incident> incidents = FXCollections.observableArrayList();
    private final FilteredList<Operator> filteredOperators = new FilteredList<>(operators, item -> true);
    private final FilteredList<Incident> filteredIncidents = new FilteredList<>(incidents, item -> true);
    private final Consumer<String> onNotice;
    private final Label registeredCount = text("—", "mono");
    private final Label enabledCount = text("—", "mono");
    private final Label registeredBreakdown = text("Loading operators…", "muted");
    private final Label enabledBreakdown = text("Loading access status…", "muted");
    private final Label openIncidentCount = text("—", "mono");
    private final Label incidentBreakdown = text("Loading incidents…", "muted");
    private final Label databaseState = text("Loading", "mono");
    private final Label databaseDetail = text("Connecting to database…", "muted");
    private final Label loadError = text("", "error");
    private final Label userPlaceholder = text("Loading operators…", "muted");
    private final Label incidentPlaceholder = text("Loading incidents…", "muted");
    private final Label userSummary = text("", "eyebrow");
    private final Label incidentSummary = text("", "eyebrow");
    private final ComboBox<String> sectorFilter = choices("All sectors");
    private final BooleanProperty databaseBusy = new SimpleBooleanProperty();
    private final BooleanProperty exportBusy = new SimpleBooleanProperty();
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "aerocon-admin-database");
        thread.setDaemon(true);
        return thread;
    });
    private boolean refreshPending;
    private boolean hasLoaded;
    private volatile boolean closed;
    private final VBox userPanel;
    private final VBox incidentPanel;
    private final ScrollPane scroll;
    private final Button usersTab;
    private final Button incidentsTab;

    public AdminView(AeroconRepository repository, Operator currentUser, Runnable onRadar, Consumer<String> onNotice) {
        this.repository = Objects.requireNonNull(repository);
        this.currentUser = Objects.requireNonNull(currentUser);
        this.onNotice = Objects.requireNonNull(onNotice);
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
        Button refresh = compactButton("↻  Refresh", "secondary-button", this::refresh);
        refresh.setId("refresh-admin");
        refresh.disableProperty().bind(databaseBusy);
        heading.getChildren().addAll(titles, headingSpacer, text("DATABASE / UTC", "eyebrow"), refresh);

        HBox stats = new HBox(12);
        stats.getChildren().addAll(stat(registeredCount, "Registered users", registeredBreakdown),
                stat(enabledCount, "Enabled accounts", enabledBreakdown),
                stat(openIncidentCount, "Open incidents", incidentBreakdown),
                stat(databaseState, "Database status", databaseDetail));
        openIncidentCount.setStyle("-fx-text-fill: #ed2863; -fx-font-size: 25;");
        databaseState.setStyle("-fx-text-fill: #00dbc1; -fx-font-size: 18;");

        userPanel = createUsersPanel();
        incidentPanel = createIncidentPanel();
        loadError.setWrapText(true);
        loadError.visibleProperty().bind(loadError.textProperty().isNotEmpty());
        loadError.managedProperty().bind(loadError.visibleProperty());
        userPanel.disableProperty().bind(databaseBusy);
        incidentPanel.disableProperty().bind(databaseBusy);
        content.getChildren().addAll(heading, stats, loadError, userPanel, incidentPanel);
        scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: #0b1226; -fx-border-width: 0;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(tabs, scroll);
        operators.addListener((javafx.collections.ListChangeListener<Operator>) change -> updateCounts());
        incidents.addListener((javafx.collections.ListChangeListener<Incident>) change -> updateCounts());
        filteredIncidents.addListener((javafx.collections.ListChangeListener<Incident>) change -> updateIncidentSummary());
        selectTab(false);
        refresh();
    }

    /** Refreshes persisted operators and incidents without blocking the JavaFX application thread. */
    public void refresh() {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(this::refresh); return; }
        if (closed) return;
        if (databaseBusy.get()) { refreshPending = true; return; }
        refreshPending = false;
        loadError.setText("");
        databaseState.setText("Loading");
        databaseDetail.setText("Reading database…");
        userPlaceholder.setText("Loading operators…");
        incidentPlaceholder.setText("Loading incidents…");
        executeDatabase(() -> new Snapshot(repository.findOperators(), repository.findIncidents()), snapshot -> {
            operators.setAll(snapshot.operators());
            incidents.setAll(snapshot.incidents());
            hasLoaded = true;
            String selectedSector = sectorFilter.getValue();
            List<String> sectors = incidents.stream().map(Incident::sector).filter(Objects::nonNull).distinct().sorted().toList();
            sectorFilter.getItems().setAll("All sectors");
            sectorFilter.getItems().addAll(sectors);
            sectorFilter.setValue(sectorFilter.getItems().contains(selectedSector) ? selectedSector : "All sectors");
            userPlaceholder.setText("No operators match these filters.");
            incidentPlaceholder.setText("No incidents match these filters.");
            databaseState.setText("Connected");
            databaseDetail.setText("Updated " + DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(Instant.now()));
            updateCounts();
        }, failure -> {
            databaseState.setText("Error");
            databaseDetail.setText(hasLoaded ? "Last loaded data shown" : "Database unavailable");
            userPlaceholder.setText("Operators could not be loaded. Use Refresh to retry.");
            incidentPlaceholder.setText("Incidents could not be loaded. Use Refresh to retry.");
            loadError.setText(message(failure) + (hasLoaded ? " Showing the last loaded data." : " Use Refresh to retry."));
        });
    }

    /** Called when this view is discarded or the application closes. */
    public void shutdown() {
        closed = true;
        databaseExecutor.shutdownNow();
    }

    private <T> void executeDatabase(Supplier<T> operation, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        if (closed) return;
        databaseBusy.set(true);
        CompletableFuture.supplyAsync(operation, databaseExecutor).whenComplete((value, failure) -> Platform.runLater(() -> {
            if (closed) return;
            databaseBusy.set(false);
            if (failure == null) onSuccess.accept(value); else onFailure.accept(unwrap(failure));
            if (refreshPending && !databaseBusy.get()) refresh();
        }));
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? "The database operation could not be completed." : value;
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
        search.setId("operator-search");
        search.setTooltip(new Tooltip("Search by name, username, or email"));
        search.setPrefWidth(210);
        search.getStyleClass().add("field");
        search.setStyle("-fx-padding: 6 10; -fx-font-size: 12;");
        ComboBox<String> role = new ComboBox<>(FXCollections.observableArrayList("All roles", "Controller", "Admin"));
        role.getSelectionModel().selectFirst();
        role.setPrefWidth(135);
        role.setStyle("-fx-padding: 0 4; -fx-font-size: 12;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button addUser = compactButton("+  Add User", "primary-button", () -> showOperatorForm(null));
        addUser.setId("add-user");
        toolbar.getChildren().addAll(title, userSummary, spacer, search, role, addUser);
        Runnable applyFilter = () -> {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            filteredOperators.setPredicate(user -> (query.isEmpty() || user.name().toLowerCase(Locale.ROOT).contains(query)
                    || user.username().toLowerCase(Locale.ROOT).contains(query)
                    || user.email().toLowerCase(Locale.ROOT).contains(query))
                    && ("All roles".equals(role.getValue()) || user.role().equals(role.getValue())));
            updateCounts();
        };
        search.textProperty().addListener((obs, old, value) -> applyFilter.run());
        role.valueProperty().addListener((obs, old, value) -> applyFilter.run());

        TableView<Operator> table = new TableView<>(filteredOperators);
        table.setId("operators-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(32);
        table.setPrefHeight(232);
        table.setMinHeight(232);
        table.setMaxHeight(232);
        table.setPlaceholder(userPlaceholder);
        TableColumn<Operator, String> name = column("USER", 155, Operator::name);
        TableColumn<Operator, String> username = column("USERNAME", 125, Operator::username);
        TableColumn<Operator, String> email = column("EMAIL", 225, Operator::email);
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
                Operator operator = getIndex() >= 0 && getIndex() < getTableView().getItems().size()
                        ? getTableView().getItems().get(getIndex()) : null;
                if (operator != null && operator.id() == currentUser.id()) {
                    delete.setDisable(true);
                    delete.setTooltip(new Tooltip("You cannot delete the account currently signed in."));
                }
                edit.setStyle("-fx-padding: 3 8; -fx-font-size: 12;");
                delete.setStyle("-fx-padding: 3 8; -fx-font-size: 12;");
                setPadding(new Insets(2, 10, 2, 10));
                HBox box = new HBox(7, edit, delete);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        table.getColumns().addAll(List.of(name, username, email, roleColumn, status, lastActive, actions));
        Label footer = text("ACCOUNT CHANGES ARE SAVED TO THE DATABASE   /   ROLE CHANGES APPLY AT THE NEXT LOGIN", "eyebrow");
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
        Button export = compactButton("↓  Export CSV", "secondary-button", this::exportIncidents);
        export.setId("export-incidents");
        export.disableProperty().bind(exportBusy);
        toolbar.getChildren().addAll(title, text("PERSISTED EVENTS / UTC", "eyebrow"), spacer, export);
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
        ComboBox<String> status = choices("Status: All", "Open", "Acknowledged", "Resolved");
        ComboBox<String> sector = sectorFilter;
        for (ComboBox<String> filter : List.of(severity, status, sector)) {
            filter.setStyle("-fx-padding: 0 4; -fx-font-size: 12;");
        }
        Runnable applyFilter = () -> {
            filteredIncidents.setPredicate(incident -> (date.getValue() == null || incident.date().equals(date.getValue()))
                    && (severity.getSelectionModel().getSelectedIndex() == 0 || incident.severity().equals(severity.getValue()))
                    && (status.getSelectionModel().getSelectedIndex() == 0 || incident.status().equals(status.getValue()))
                    && (sector.getSelectionModel().getSelectedIndex() <= 0 || Objects.equals(incident.sector(), sector.getValue())));
            updateIncidentSummary();
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
        table.setId("incidents-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(32);
        table.setPrefHeight(134);
        table.setMinHeight(134);
        table.setMaxHeight(134);
        table.setPlaceholder(incidentPlaceholder);
        table.getColumns().addAll(List.of(
                column("INCIDENT ID", 125, Incident::code), column("TIME / UTC", 120, Incident::time),
                column("FLIGHT PAIR", 190, Incident::flights), column("SEVERITY", 110, Incident::severity),
                column("EVENT DETAIL", 365, Incident::detail), column("STATUS", 125, Incident::status)));
        table.setRowFactory(view -> new TableRow<>() {
            @Override protected void updateItem(Incident item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && item.status().equals("Open")
                        ? "-fx-background-color: #301a31; -fx-text-background-color: #ed6190;" : "");
            }
        });
        Label footer = text("EVENTS STORED IN THE DATABASE   /   ALL TIMES UTC   /   EXPORT INCLUDES VISIBLE RECORDS", "eyebrow");
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
        long open = incidents.stream().filter(incident -> "Open".equalsIgnoreCase(incident.status())).count();
        long acknowledged = incidents.stream().filter(incident -> "Acknowledged".equalsIgnoreCase(incident.status())).count();
        long resolved = incidents.stream().filter(incident -> "Resolved".equalsIgnoreCase(incident.status())).count();
        openIncidentCount.setText(String.format("%02d", open));
        incidentBreakdown.setText(acknowledged + " acknowledged / " + resolved + " resolved");
        updateIncidentSummary();
    }

    private void updateIncidentSummary() {
        incidentSummary.setText(String.format("%02d OF %02d INCIDENTS", filteredIncidents.size(), incidents.size()));
    }

    private void showOperatorForm(Operator existing) {
        boolean edit = existing != null;
        boolean self = edit && existing.id() == currentUser.id();
        Dialog<ButtonType> dialog = createDialog(edit ? "Edit User" : "Add New Operator");
        TextField name = field(existing == null ? "" : existing.name(), "Full name");
        name.setId("operator-name");
        TextField username = field(existing == null ? "" : existing.username(), "e.g. maya.chen");
        username.setId("operator-username");
        TextField email = field(existing == null ? "" : existing.email(), "operator@aerocon.atc");
        email.setId("operator-email");
        PasswordField password = new PasswordField();
        password.setPromptText(edit ? "Leave blank to keep the current password" : "At least 8 characters");
        password.setId("operator-password");
        ComboBox<String> role = choices("Controller", "Admin");
        role.setId("operator-role");
        role.setMaxWidth(Double.MAX_VALUE);
        if (edit) role.setValue(existing.role());
        role.setDisable(self);
        TextField clearance = field(existing == null ? "Sector 07 / London Upper" : existing.clearance(), "Access clearance / sector");
        clearance.setId("operator-clearance");
        CheckBox enabled = new CheckBox("Account enabled");
        enabled.setId("operator-enabled");
        enabled.setSelected(existing == null || existing.enabled());
        enabled.setDisable(self);
        Label error = text("", "error");
        error.setId("operator-error");
        error.setWrapText(true);
        VBox form = new VBox(10, text(edit ? "EDIT USER" : "ADD NEW OPERATOR", "eyebrow"),
                text(edit ? "Update operator access" : "Create an operator account", "title"),
                text("Full name", "muted"), name, text("Username", "muted"), username,
                text("Email address", "muted"), email, text(edit ? "New password (optional)" : "Password", "muted"), password,
                text("Role selection", "muted"), role, text("Access clearance / sector", "muted"), clearance, enabled, error);
        if (self) {
            Label note = text("You cannot disable your own account or change its role while signed in.", "muted");
            note.setWrapText(true);
            form.getChildren().add(note);
        }
        form.setPadding(new Insets(12, 8, 8, 8));
        form.setPrefWidth(430);
        ScrollPane formScroll = new ScrollPane(form);
        formScroll.setFitToWidth(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.setPrefViewportWidth(450);
        double availableHeight = getScene() == null ? 600 : getScene().getWindow().getHeight();
        formScroll.setPrefViewportHeight(Math.max(300, Math.min(590, availableHeight - 190)));
        dialog.getDialogPane().setContent(formScroll);
        dialog.getDialogPane().setMinWidth(490);
        ButtonType confirm = new ButtonType(edit ? "Confirm Changes" : "Create Account", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        styleDialogButtons(dialog, confirm, false);
        Button confirmButton = (Button) dialog.getDialogPane().lookupButton(confirm);
        confirmButton.setId("save-operator");
        Node cancelButton = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        BooleanProperty saving = new SimpleBooleanProperty();
        confirmButton.disableProperty().bind(saving);
        cancelButton.disableProperty().bind(saving);
        formScroll.disableProperty().bind(saving);
        dialog.setOnCloseRequest(event -> { if (saving.get()) event.consume(); });
        dialog.setOnHidden(event -> password.clear());
        confirmButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (name.getText().trim().isEmpty()) {
                error.setText("Enter the operator's full name.");
            } else if (!username.getText().trim().matches("[A-Za-z0-9._-]{3,64}")) {
                error.setText("Username must contain 3–64 letters, digits, dots, underscores, or hyphens.");
            } else if (!email.getText().trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                error.setText("Enter a valid email address.");
            } else if ((!edit || !password.getText().isEmpty()) && password.getText().length() < 8) {
                error.setText("The password must contain at least 8 characters.");
            } else if (operators.stream().anyMatch(user -> (!edit || user.id() != existing.id())
                    && user.username().equalsIgnoreCase(username.getText().trim()))) {
                error.setText("An operator with this username already exists.");
            } else if (operators.stream().anyMatch(user -> (!edit || user.id() != existing.id())
                    && user.email().equalsIgnoreCase(email.getText().trim()))) {
                error.setText("An operator with this email address already exists.");
            } else if (clearance.getText().trim().isEmpty()) {
                error.setText("Enter an access clearance or sector.");
            } else if (self && (!enabled.isSelected() || !Objects.equals(existing.role(), role.getValue()))) {
                error.setText("Your own account must remain enabled with its current role.");
            } else {
                String savedName = name.getText().trim();
                String savedUsername = username.getText().trim();
                String savedEmail = email.getText().trim();
                String savedRole = role.getValue();
                boolean savedEnabled = enabled.isSelected();
                String savedClearance = clearance.getText().trim();
                char[] savedPassword = password.getText().toCharArray();
                error.setText("Saving changes to the database…");
                saving.set(true);
                confirmButton.setText("Saving…");
                executeDatabase(() -> {
                    try {
                        return edit
                                ? repository.updateOperator(existing.id(), savedName, savedUsername, savedEmail, savedRole, savedEnabled, savedClearance, savedPassword)
                                : repository.createOperator(savedName, savedUsername, savedEmail, savedRole, savedEnabled, savedClearance, savedPassword);
                    } finally { Arrays.fill(savedPassword, '\0'); }
                }, saved -> {
                    saving.set(false);
                    password.clear();
                    dialog.close();
                    onNotice.accept(edit ? "Operator account updated in the database." : "Operator account created in the database.");
                    refresh();
                }, failure -> {
                    saving.set(false);
                    confirmButton.setText(edit ? "Confirm Changes" : "Create Account");
                    error.setText(message(failure));
                    formScroll.setVvalue(1);
                });
            }
            if (!saving.get()) formScroll.setVvalue(1);
        });
        dialog.show();
    }

    private void showDeleteForm(Operator operator) {
        if (operator == null) return;
        if (operator.id() == currentUser.id()) {
            onNotice.accept("You cannot delete the account currently signed in.");
            return;
        }
        Dialog<ButtonType> dialog = createDialog("Delete User");
        Label warning = text("Permanently delete this operator account from the database? The operator will no longer be able to sign in.", "muted");
        warning.setWrapText(true);
        Label error = text("", "error");
        error.setWrapText(true);
        error.setId("delete-operator-error");
        VBox content = new VBox(14, text("DELETE USER", "eyebrow"), text(operator.name(), "title"),
                text(operator.username() + " / " + operator.email(), "mono"), warning, error);
        content.setPadding(new Insets(12, 8, 16, 8));
        content.setPrefWidth(410);
        dialog.getDialogPane().setContent(content);
        ButtonType delete = new ButtonType("Delete User", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, delete);
        styleDialogButtons(dialog, delete, true);
        Button deleteButton = (Button) dialog.getDialogPane().lookupButton(delete);
        deleteButton.setId("delete-operator");
        Node cancelButton = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        BooleanProperty deleting = new SimpleBooleanProperty();
        deleteButton.disableProperty().bind(deleting);
        cancelButton.disableProperty().bind(deleting);
        dialog.setOnCloseRequest(event -> { if (deleting.get()) event.consume(); });
        deleteButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            deleting.set(true);
            deleteButton.setText("Deleting…");
            error.setText("Deleting the account from the database…");
            executeDatabase(() -> { repository.deleteOperator(operator.id()); return true; }, ignored -> {
                deleting.set(false);
                dialog.close();
                onNotice.accept("Operator account deleted from the database.");
                refresh();
            }, failure -> {
                deleting.set(false);
                deleteButton.setText("Delete User");
                error.setText(message(failure));
            });
        });
        dialog.show();
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
        List<Incident> exported = List.copyOf(filteredIncidents);
        StringBuilder csv = new StringBuilder("Incident ID,Date,Time UTC,Flight pair,Severity,Event detail,Status,Sector\r\n");
        for (Incident incident : exported) {
            csv.append(String.join(",", quote(incident.code()), quote(incident.date().toString()), quote(incident.time()),
                    quote(incident.flights()), quote(incident.severity()), quote(incident.detail()), quote(incident.status()), quote(incident.sector())))
                    .append("\r\n");
        }
        exportBusy.set(true);
        CompletableFuture.runAsync(() -> {
            try { Files.writeString(file.toPath(), csv, StandardCharsets.UTF_8); }
            catch (IOException exception) { throw new CompletionException(exception); }
        }, databaseExecutor).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (closed) return;
            exportBusy.set(false);
            onNotice.accept(failure == null ? "Exported " + exported.size() + " incidents to " + file.getName() + "."
                    : "Could not save the CSV file: " + message(unwrap(failure)));
        }));
    }

    private static String quote(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }

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

    private record Snapshot(List<Operator> operators, List<Incident> incidents) { }
}
