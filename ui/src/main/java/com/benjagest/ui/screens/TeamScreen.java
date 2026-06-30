package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.service.AuthSession;
import com.benjagest.ui.service.SettingsApiClient;
import com.benjagest.ui.support.*;
import java.util.*;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import javafx.stage.*;
import javafx.util.*;

/**
 * Modulo EQUIPO (asesoria): miembros, asignaciones, colaboraciones,
 * delegaciones. Extraido del God Object en UIR-6.
 */
public class TeamScreen extends ScreenBase {

    private final AltaApiClient altaApiClient;
    private final SettingsApiClient settingsApiClient;

    public TeamScreen(AltaApiClient altaApiClient, SettingsApiClient settingsApiClient,
                      Function<String, String> tt, Router router) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
        this.settingsApiClient = settingsApiClient;
    }

    public void show() {
        Task<TeamBundle> task = new Task<>() {
            @Override protected TeamBundle call() throws Exception {
                // Cargamos secuencialmente desde el thread del Task. La
                // UI se actualiza cuando todas las llamadas terminan.
                // 403 en la primera (listTeamMembers) => no OWNER.
                List<com.benjagest.ui.model.TeamMember> members = altaApiClient.listTeamMembers();
                List<com.benjagest.ui.model.TeamAssignment> assignments =
                        altaApiClient.listTeamAssignmentsWithModules();
                List<com.benjagest.ui.model.CustomerPortfolioEntry> clients =
                        altaApiClient.listAdvisoryPortfolio();
                List<CompanyModuleEntry> modules = settingsApiClient.listModules();
                // L4-7: colaboraciones. Defensivo — si los endpoints
                // fallan por cualquier razón (módulo no cargado, BD
                // legacy), devolvemos listas vacías y la tab muestra
                // estado vacío en lugar de petar la pantalla entera.
                List<com.benjagest.ui.model.CollabEntry> outgoing;
                List<com.benjagest.ui.model.CollabEntry> incoming;
                List<com.benjagest.ui.model.CollabEntry> active;
                try { outgoing = altaApiClient.listOutgoingCollabs(); }
                catch (Exception ex) { outgoing = List.of(); }
                try { incoming = altaApiClient.listIncomingCollabs(); }
                catch (Exception ex) { incoming = List.of(); }
                try { active = altaApiClient.listActiveCollabs(); }
                catch (Exception ex) { active = List.of(); }
                return new TeamBundle(members, assignments, clients, modules,
                        outgoing, incoming, active);
            }
        };
        task.setOnSucceeded(ev -> setCenterAnimated(scroll(teamView(task.getValue()))));
        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            String msg = err != null && err.getMessage() != null && err.getMessage().contains("403")
                    ? t("team.forbidden")
                    : t("team.load_failed");
            setCenterAnimated(scroll(errorPanel(msg)));
        });
        start(task, "team-load");
    }

    private record TeamBundle(
            List<com.benjagest.ui.model.TeamMember> members,
            List<com.benjagest.ui.model.TeamAssignment> assignments,
            List<com.benjagest.ui.model.CustomerPortfolioEntry> clients,
            List<CompanyModuleEntry> modules,
            // L4-7: colaboraciones inter-asesoría
            List<com.benjagest.ui.model.CollabEntry> outgoingCollabs,
            List<com.benjagest.ui.model.CollabEntry> incomingCollabs,
            List<com.benjagest.ui.model.CollabEntry> activeCollabs
    ) {}

    private VBox teamView(TeamBundle bundle) {
        VBox content = content();
        Label title = new Label(t("team.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("team.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-users-cog", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, titleBox, moduleIcon, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        // Badge "Modo Propietario · ves todo" para el OWNER/ADMIN que
        // entra al módulo Equipo. Implícito antes (modulesVisibleInClient
        // devuelve MODULE_ALL para OWNER); ahora se hace visible para
        // que el usuario tenga feedback claro de su contexto.
        if (AuthSession.get().isOwnerOrAdmin()) {
            Label ownerBadge = new Label(t("team.owner_mode_badge"));
            ownerBadge.setGraphic(icon("fas-crown"));
            ownerBadge.setStyle("-fx-background-color: #fff7e6; "
                    + "-fx-border-color: #ffd591; -fx-border-radius: 12; "
                    + "-fx-background-radius: 12; -fx-padding: 6 12 6 12; "
                    + "-fx-text-fill: #ad6800;");
            header.getChildren().add(ownerBadge);
        }

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab membersTab = new Tab(t("team.tab.members"), teamMembersTab(bundle));
        membersTab.setGraphic(icon("fas-users"));
        Tab assignmentsTab = new Tab(t("team.tab.assignments"), teamAssignmentsTab(bundle));
        assignmentsTab.setGraphic(icon("fas-tasks"));
        Tab delegationsTab = new Tab(t("team.tab.delegations"), teamDelegationsTab(bundle));
        delegationsTab.setGraphic(icon("fas-calendar-times"));
        // L4-7 — nueva tab Colaboradores
        Tab collabTab = new Tab(t("team.tab.collaborators"), teamCollaboratorsTab(bundle));
        collabTab.setGraphic(icon("fas-handshake"));
        tabs.getTabs().addAll(membersTab, assignmentsTab, delegationsTab, collabTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        content.getChildren().addAll(header, tabs);
        return content;
    }

    // ----- Tab 1: Empleados -------------------------------------------------

    private Node teamMembersTab(TeamBundle bundle) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        Label hint = new Label(t("team.members.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.TeamMember> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("team.members.empty")));

        // L4-5: marca al usuario actual con "(tú)" en la columna nombre,
        // para que el OWNER se identifique de un vistazo en su propia
        // lista de equipo (a menudo solo está él al principio).
        String myUserId = AuthSession.get().userId();
        TableColumn<com.benjagest.ui.model.TeamMember, String> cName =
                new TableColumn<>(t("team.members.col.name"));
        cName.setCellValueFactory(c -> {
            String name = c.getValue().displayName();
            if (myUserId != null && myUserId.equals(c.getValue().userId())) {
                name = name + " — " + t("team.members.you");
            }
            return new SimpleStringProperty(name);
        });
        cName.setPrefWidth(240);
        TableColumn<com.benjagest.ui.model.TeamMember, String> cEmail =
                new TableColumn<>(t("team.members.col.email"));
        cEmail.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().email()));
        cEmail.setPrefWidth(260);
        TableColumn<com.benjagest.ui.model.TeamMember, String> cRole =
                new TableColumn<>(t("team.members.col.role"));
        cRole.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeMemberRole(c.getValue().roleName())));
        cRole.setPrefWidth(140);
        table.getColumns().addAll(List.of(cName, cEmail, cRole));
        table.setItems(FXCollections.observableArrayList(bundle.members()));

        box.getChildren().addAll(hint, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    // ----- Tab 2: Asignaciones ---------------------------------------------

    private Node teamAssignmentsTab(TeamBundle bundle) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));

        Label hint = new Label(t("team.assign.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // ----- Panel izquierdo: clientes con checkbox -----
        // Mostramos toda la cartera (portfolio) — incluye vinculados y no
        // vinculados. El backend valida en bulkAssign que parent_company_id
        // del cliente apunta a la asesoría logueada.
        ObservableList<ClientRow> clientRows = FXCollections.observableArrayList();

        // FILA VIRTUAL "Mi gestión" — la propia asesoría se asigna a sí
        // misma como cliente (V64 self-link). Benjamin pidió que se pueda
        // repartir el trabajo INTERNO de la asesoría entre empleados con
        // módulos concretos, igual que con cualquier otro cliente. La
        // backend valida bypass en validateClientBelongsToAdvisory.
        String advisoryId = AuthSession.get().activeCompanyId();
        String advisoryName = AuthSession.get().activeCompanyLegalName();
        if (advisoryId != null && !advisoryId.isBlank()) {
            String name = (advisoryName == null || advisoryName.isBlank())
                    ? t("team.assign.my_company") : advisoryName;
            // Reusamos CustomerPortfolioEntry — solo necesitamos un
            // linkedCompanyId y displayName.
            com.benjagest.ui.model.CustomerPortfolioEntry self =
                    new com.benjagest.ui.model.CustomerPortfolioEntry(
                            advisoryId,
                            "★ " + t("team.assign.my_company") + " — " + name,
                            null, null,
                            "ADVISORY",
                            null, null, null,
                            advisoryId,
                            false, false, true);
            clientRows.add(new ClientRow(self));
        }

        for (com.benjagest.ui.model.CustomerPortfolioEntry c : bundle.clients()) {
            // Solo clientes que tienen shadow company creada (linkedCompanyId
            // != null) son asignables — las asignaciones se hacen contra
            // companies.id, no contra customers.id. Si todavía no se ha
            // iniciado la gestión, no podemos asignar empleados (esa decisión
            // se toma cuando el OWNER hace doble-click en "Iniciar gestión").
            if (c.linkedCompanyId() == null || c.linkedCompanyId().isBlank()) continue;
            // No duplicar si la asesoría aparece por self-link en portfolio
            if (advisoryId != null && advisoryId.equals(c.linkedCompanyId())) continue;
            clientRows.add(new ClientRow(c));
        }
        TableView<ClientRow> clientTable = new TableView<>();
        clientTable.getStyleClass().add("data-table");
        clientTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        clientTable.setPlaceholder(new Label(t("team.assign.empty.clients")));
        clientTable.setEditable(true);

        TableColumn<ClientRow, Boolean> cPick = new TableColumn<>("");
        cPick.setCellValueFactory(c -> c.getValue().selected);
        cPick.setCellFactory(javafx.scene.control.cell.CheckBoxTableCell.forTableColumn(cPick));
        cPick.setPrefWidth(40);
        cPick.setEditable(true);
        TableColumn<ClientRow, String> cClient = new TableColumn<>(t("team.assign.col.client"));
        cClient.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().displayName()));
        clientTable.getColumns().addAll(List.of(cPick, cClient));
        clientTable.setItems(clientRows);
        VBox.setVgrow(clientTable, Priority.ALWAYS);

        // Barra superior: botones de selección rápida.
        Button selectAllBtn = new Button(t("team.assign.btn.select_all"));
        selectAllBtn.setGraphic(icon("fas-check-double"));
        selectAllBtn.setOnAction(ev -> clientRows.forEach(r -> r.selected.set(true)));
        Button clearAllBtn = new Button(t("team.assign.btn.select_none"));
        clearAllBtn.setGraphic(icon("fas-times-circle"));
        clearAllBtn.setOnAction(ev -> clientRows.forEach(r -> r.selected.set(false)));
        HBox selectBar = new HBox(8, selectAllBtn, clearAllBtn);
        selectBar.setAlignment(Pos.CENTER_LEFT);
        selectBar.setPadding(new Insets(0, 0, 6, 0));
        VBox clientsBox = new VBox(0, selectBar, clientTable);
        VBox.setVgrow(clientTable, Priority.ALWAYS);

        // ----- Panel derecho: formulario de asignación -----
        ComboBox<com.benjagest.ui.model.TeamMember> employeeCombo = new ComboBox<>();
        employeeCombo.getItems().addAll(bundle.members());
        employeeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.TeamMember m) {
                return m == null ? "" : m.label();
            }
            @Override public com.benjagest.ui.model.TeamMember fromString(String s) { return null; }
        });
        employeeCombo.setPromptText(t("team.assign.field.employee"));
        employeeCombo.setMaxWidth(Double.MAX_VALUE);

        // El combo guarda el código (ADVISOR/ACCOUNTANT/…) pero muestra el
        // label traducido del idioma actual con un StringConverter. Así
        // evitamos enviar etiquetas españolas al backend y mantenemos i18n.
        ComboBox<String> roleCombo = new ComboBox<>(FXCollections.observableArrayList(
                "ADVISOR", "ACCOUNTANT", "EMPLOYEE", "VIEWER"));
        roleCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                return code == null ? "" : humanizeAssignmentRole(code);
            }
            @Override public String fromString(String s) { return null; }
        });
        roleCombo.setValue("ADVISOR");
        roleCombo.setMaxWidth(Double.MAX_VALUE);

        // Checkboxes por módulo. Solo mostramos los módulos PRINCIPALES
        // que aparecen en el sidebar del cliente, para mantener el panel
        // de asignación limpio y alineado con lo que el empleado va a ver.
        // Benjamin: 'demasiados módulos, deberíamos globalizar'.
        //
        // Lista cerrada — refleja el sidebar de un cliente: facturación,
        // compras, contabilidad, fiscal, laboral, fichajes, autónomos,
        // agenda, informes, DEHú. Excluye internos como certificados
        // digitales, eventos SIF, plan contable, etc. (son sub-piezas
        // de los módulos principales y no se reparten independientemente).
        java.util.Set<String> ASSIGNABLE_SLUGS = java.util.Set.of(
                "billing", "purchases", "accounting", "tax", "labor",
                "timeclock", "time-clock", "self-employed", "reta",
                "calendar", "reports", "notifications", "dehu"
        );
        VBox modulesBox = new VBox(4);
        modulesBox.setPadding(new Insets(4, 0, 4, 0));
        List<CheckBox> moduleChecks = new ArrayList<>();
        CheckBox allModules = new CheckBox(t("team.assign.modules.all"));
        allModules.setSelected(true);
        modulesBox.getChildren().add(allModules);
        // Submódulos del catálogo activos. Cuando "all" está marcado, los
        // checkboxes individuales se ignoran (asignación abierta).
        for (CompanyModuleEntry m : bundle.modules()) {
            if (m == null || m.slug() == null || m.slug().isBlank()) continue;
            if (!ASSIGNABLE_SLUGS.contains(m.slug())) continue;
            CheckBox cb = new CheckBox(m.label());
            cb.setUserData(m.slug());
            cb.disableProperty().bind(allModules.selectedProperty());
            moduleChecks.add(cb);
            modulesBox.getChildren().add(cb);
        }
        ScrollPane modulesScroll = new ScrollPane(modulesBox);
        modulesScroll.setFitToWidth(true);
        modulesScroll.setPrefViewportHeight(180);
        modulesScroll.getStyleClass().add("settings-section");

        TextField notesField = new TextField();
        notesField.setPromptText(t("team.assign.field.notes"));

        Button assignBtn = new Button(t("team.assign.btn.assign"));
        assignBtn.setGraphic(icon("fas-share-square"));
        assignBtn.getStyleClass().add("primary-button");
        assignBtn.setOnAction(ev -> {
            List<String> selectedIds = new ArrayList<>();
            for (ClientRow r : clientRows) {
                if (r.selected.get() && r.companyId != null) selectedIds.add(r.companyId);
            }
            if (selectedIds.isEmpty()) {
                showError(t("team.assign.no_selection.title"), t("team.assign.no_selection.body"));
                return;
            }
            com.benjagest.ui.model.TeamMember emp = employeeCombo.getValue();
            if (emp == null) {
                showError(t("team.assign.no_employee.title"), t("team.assign.no_employee.body"));
                return;
            }
            List<String> moduleSlugs = new ArrayList<>();
            if (!allModules.isSelected()) {
                for (CheckBox cb : moduleChecks) {
                    if (cb.isSelected() && cb.getUserData() instanceof String s) {
                        moduleSlugs.add(s);
                    }
                }
            }
            String empId = emp.userId();
            String role = roleCombo.getValue();
            String notes = notesField.getText();
            Task<String> save = new Task<>() {
                @Override protected String call() throws Exception {
                    return altaApiClient.bulkAssignClients(empId, selectedIds, role, moduleSlugs, notes);
                }
            };
            save.setOnSucceeded(s -> {
                showInfo(t("team.assign.ok.title"), t("team.assign.ok.body") + " " + selectedIds.size());
                show();
            });
            save.setOnFailed(s -> showError(t("team.assign.fail.title"), t("team.assign.fail.body")));
            start(save, "team-bulk-assign");
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(8));
        form.add(new Label(t("team.assign.field.employee")), 0, 0);
        form.add(employeeCombo, 1, 0);
        form.add(new Label(t("team.assign.field.role")), 0, 1);
        form.add(roleCombo, 1, 1);
        form.add(new Label(t("team.assign.field.modules")), 0, 2);
        form.add(modulesScroll, 1, 2);
        form.add(new Label(t("team.assign.field.notes")), 0, 3);
        form.add(notesField, 1, 3);
        form.add(assignBtn, 1, 4);
        javafx.scene.layout.ColumnConstraints col0 = new javafx.scene.layout.ColumnConstraints();
        col0.setMinWidth(140);
        javafx.scene.layout.ColumnConstraints col1 = new javafx.scene.layout.ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        col1.setFillWidth(true);
        form.getColumnConstraints().addAll(col0, col1);

        VBox right = new VBox(10, form);
        right.setPadding(new Insets(0, 0, 0, 16));

        SplitPane split = new SplitPane(clientsBox, right);
        split.setDividerPositions(0.50);
        VBox.setVgrow(split, Priority.ALWAYS);

        // ----- Matriz actual de asignaciones (abajo) -----
        TableView<com.benjagest.ui.model.TeamAssignment> matrix = new TableView<>();
        matrix.getStyleClass().add("data-table");
        matrix.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        matrix.setPlaceholder(new Label(t("team.assign.empty.matrix")));
        // Selección múltiple para poder eliminar varias asignaciones a la vez.
        matrix.getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);
        java.util.Map<String, String> clientNameById = new java.util.HashMap<>();
        // Añadir Mi gestión (asesoría) primero para que las asignaciones
        // donde client_company_id == advisory_company_id muestren la
        // etiqueta humana en lugar del UUID. Benjamin: 'cuando le asigno
        // mi gestion a un empleado en el campo cliente aparece el id'.
        String myAdvisoryId = AuthSession.get().activeCompanyId();
        String myAdvisoryName = AuthSession.get().activeCompanyLegalName();
        if (myAdvisoryId != null && !myAdvisoryId.isBlank()) {
            String label = "★ " + t("team.assign.my_company")
                    + (myAdvisoryName == null || myAdvisoryName.isBlank()
                            ? "" : " — " + myAdvisoryName);
            clientNameById.put(myAdvisoryId, label);
        }
        for (com.benjagest.ui.model.CustomerPortfolioEntry c : bundle.clients()) {
            if (c.linkedCompanyId() != null) clientNameById.put(c.linkedCompanyId(), labelOf(c));
        }
        java.util.Map<String, String> memberLabelById = new java.util.HashMap<>();
        for (com.benjagest.ui.model.TeamMember m : bundle.members()) memberLabelById.put(m.userId(), m.label());

        TableColumn<com.benjagest.ui.model.TeamAssignment, String> mClient =
                new TableColumn<>(t("team.assign.col.client"));
        mClient.setCellValueFactory(c -> new SimpleStringProperty(
                clientNameById.getOrDefault(c.getValue().clientCompanyId(), c.getValue().clientCompanyId())));
        mClient.setPrefWidth(240);
        TableColumn<com.benjagest.ui.model.TeamAssignment, String> mEmployee =
                new TableColumn<>(t("team.assign.col.employee"));
        mEmployee.setCellValueFactory(c -> new SimpleStringProperty(
                memberLabelById.getOrDefault(c.getValue().employeeUserId(), c.getValue().employeeUserId())));
        mEmployee.setPrefWidth(220);
        TableColumn<com.benjagest.ui.model.TeamAssignment, String> mRole =
                new TableColumn<>(t("team.assign.col.role"));
        mRole.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeAssignmentRole(c.getValue().roleInClient())));
        mRole.setPrefWidth(140);
        // Mapa slug → label localizado del catálogo (mismo que ve el OWNER
        // en la pestaña Módulos de Configuración). Evita mostrar "billing,
        // purchases" y enseña "Facturación, Compras".
        java.util.Map<String, String> moduleLabelBySlug = new java.util.HashMap<>();
        for (CompanyModuleEntry m : bundle.modules()) {
            if (m != null && m.slug() != null) moduleLabelBySlug.put(m.slug(), m.label());
        }
        TableColumn<com.benjagest.ui.model.TeamAssignment, String> mModules =
                new TableColumn<>(t("team.assign.col.modules"));
        mModules.setCellValueFactory(c -> {
            List<String> ms = c.getValue().moduleSlugs();
            if (ms == null || ms.isEmpty()) {
                return new SimpleStringProperty(t("team.assign.modules.all"));
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ms.size(); i++) {
                if (i > 0) sb.append(", ");
                String slug = ms.get(i);
                sb.append(moduleLabelBySlug.getOrDefault(slug, slug));
            }
            return new SimpleStringProperty(sb.toString());
        });
        matrix.getColumns().addAll(List.of(mClient, mEmployee, mRole, mModules));
        matrix.setItems(FXCollections.observableArrayList(bundle.assignments()));

        Button deleteBtn = new Button(t("team.assign.btn.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.setDisable(true);
        matrix.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<com.benjagest.ui.model.TeamAssignment>)
                c -> deleteBtn.setDisable(matrix.getSelectionModel().getSelectedItems().isEmpty()));
        deleteBtn.setOnAction(ev -> {
            var selection = new ArrayList<>(matrix.getSelectionModel().getSelectedItems());
            if (selection.isEmpty()) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("team.assign.delete.confirm.title"));
            confirm.setHeaderText(t("team.assign.delete.confirm.title"));
            confirm.setContentText(selection.size() == 1
                    ? t("team.assign.delete.confirm.body")
                    : t("team.assign.delete.confirm.body_many").replace("{n}", String.valueOf(selection.size())));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Integer> del = new Task<>() {
                        @Override protected Integer call() throws Exception {
                            int ok = 0;
                            for (var a : selection) {
                                try {
                                    altaApiClient.deleteTeamAssignment(a.id());
                                    ok++;
                                } catch (Exception ex) {
                                    // Continúa con las demás aunque alguna falle.
                                }
                            }
                            return ok;
                        }
                    };
                    del.setOnSucceeded(s -> show());
                    del.setOnFailed(s -> showError(t("team.assign.delete.fail.title"),
                            t("team.assign.fail.body")));
                    start(del, "team-delete");
                }
            });
        });

        VBox matrixBox = new VBox(8,
                new Label(t("team.assign.col.modules") + " · " + t("team.tab.assignments")),
                matrix, deleteBtn);
        VBox.setVgrow(matrix, Priority.ALWAYS);
        matrixBox.setPadding(new Insets(8, 0, 0, 0));

        box.getChildren().addAll(hint, split, matrixBox);
        return box;
    }

    /** Fila auxiliar para el TableView de selección de clientes. */
    private static final class ClientRow {
        final String companyId;
        final String legalName;
        final String taxIdentifier;
        final javafx.beans.property.BooleanProperty selected =
                new javafx.beans.property.SimpleBooleanProperty(false);
        ClientRow(com.benjagest.ui.model.CustomerPortfolioEntry c) {
            this.companyId = c.linkedCompanyId();
            this.legalName = c.legalName();
            this.taxIdentifier = c.taxIdentifier();
        }
        String displayName() {
            if (taxIdentifier == null || taxIdentifier.isBlank()) return safe(legalName);
            return safe(legalName) + " · " + taxIdentifier;
        }
        private static String safe(String s) { return s == null ? "" : s; }
    }

    private String labelOf(com.benjagest.ui.model.CustomerPortfolioEntry c) {
        if (c.taxIdentifier() == null || c.taxIdentifier().isBlank()) {
            return c.legalName() == null ? "" : c.legalName();
        }
        return (c.legalName() == null ? "" : c.legalName()) + " · " + c.taxIdentifier();
    }

    // ----- Tab 3: Delegaciones ---------------------------------------------

    // ----- Tab 4: Colaboradores (L4-7) ------------------------------------

    /**
     * L4-7 — Tab que gestiona las colaboraciones entre asesorías:
     * <ul>
     *   <li>Si tengo invitaciones recibidas (pending), sección arriba
     *       con tabla y botones Aceptar / Rechazar.</li>
     *   <li>Sección "Colaboradoras activas" con tabla + botón Revocar.</li>
     *   <li>Sección "Invitaciones enviadas pendientes" con botón Revocar.</li>
     *   <li>Botón "Invitar asesoría colaboradora" abre diálogo email.</li>
     * </ul>
     */
    private Node teamCollaboratorsTab(TeamBundle bundle) {
        VBox box = new VBox(16);
        box.setPadding(new Insets(16));

        Label hint = new Label(t("team.collab.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        box.getChildren().add(hint);

        // Invitaciones recibidas — solo si las hay
        if (!bundle.incomingCollabs().isEmpty()) {
            Label inTitle = new Label(t("team.collab.incoming.title"));
            inTitle.getStyleClass().add("settings-section-title");
            TableView<com.benjagest.ui.model.CollabEntry> inTable = collabTable(
                    bundle.incomingCollabs(), true, false);
            Button acceptBtn = new Button(t("team.collab.accept"));
            acceptBtn.setGraphic(icon("fas-check"));
            acceptBtn.getStyleClass().add("primary-button");
            acceptBtn.setDisable(true);
            Button rejectBtn = new Button(t("team.collab.reject"));
            rejectBtn.setGraphic(icon("fas-times"));
            rejectBtn.setDisable(true);
            inTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
                boolean enabled = nv != null && nv.isPending();
                acceptBtn.setDisable(!enabled);
                rejectBtn.setDisable(!enabled);
            });
            acceptBtn.setOnAction(ev -> {
                var sel = inTable.getSelectionModel().getSelectedItem();
                if (sel == null) return;
                runCollabAction(() -> altaApiClient.acceptCollab(sel.id()),
                        "team.collab.accept.ok", "team.collab.accept.fail");
            });
            rejectBtn.setOnAction(ev -> {
                var sel = inTable.getSelectionModel().getSelectedItem();
                if (sel == null) return;
                runCollabAction(() -> altaApiClient.rejectCollab(sel.id()),
                        "team.collab.reject.ok", "team.collab.reject.fail");
            });
            HBox inActions = new HBox(8, acceptBtn, rejectBtn);
            box.getChildren().addAll(inTitle, inTable, inActions);
        }

        // Colaboradoras activas
        Label actTitle = new Label(t("team.collab.active.title"));
        actTitle.getStyleClass().add("settings-section-title");
        TableView<com.benjagest.ui.model.CollabEntry> actTable = collabTable(
                bundle.activeCollabs(), false, true);
        Button revokeActiveBtn = new Button(t("team.collab.revoke"));
        revokeActiveBtn.setGraphic(icon("fas-unlink"));
        revokeActiveBtn.setDisable(true);
        actTable.getSelectionModel().selectedItemProperty().addListener(
                (o, ov, nv) -> revokeActiveBtn.setDisable(nv == null));
        revokeActiveBtn.setOnAction(ev -> confirmRevokeCollab(actTable));
        box.getChildren().addAll(actTitle, actTable, revokeActiveBtn);

        // Invitaciones enviadas pendientes
        java.util.List<com.benjagest.ui.model.CollabEntry> outgoingPending =
                bundle.outgoingCollabs().stream()
                        .filter(com.benjagest.ui.model.CollabEntry::isPending)
                        .toList();
        Label outTitle = new Label(t("team.collab.outgoing.title"));
        outTitle.getStyleClass().add("settings-section-title");
        TableView<com.benjagest.ui.model.CollabEntry> outTable = collabTable(
                outgoingPending, false, false);
        Button revokeOutBtn = new Button(t("team.collab.revoke"));
        revokeOutBtn.setGraphic(icon("fas-unlink"));
        revokeOutBtn.setDisable(true);
        outTable.getSelectionModel().selectedItemProperty().addListener(
                (o, ov, nv) -> revokeOutBtn.setDisable(nv == null));
        revokeOutBtn.setOnAction(ev -> confirmRevokeCollab(outTable));
        box.getChildren().addAll(outTitle, outTable, revokeOutBtn);

        // Botón invitar
        Button inviteBtn = new Button(t("team.collab.invite"));
        inviteBtn.setGraphic(icon("fas-paper-plane"));
        inviteBtn.getStyleClass().add("primary-button");
        inviteBtn.setOnAction(ev -> openInviteCollabDialog());

        Region spacer2 = new Region();
        VBox.setVgrow(spacer2, Priority.ALWAYS);
        HBox bottomBar = new HBox(8, spacer2, inviteBtn);
        bottomBar.setAlignment(Pos.CENTER_RIGHT);
        box.getChildren().add(bottomBar);

        return box;
    }

    /** Tabla común para listar colaboraciones. */
    private TableView<com.benjagest.ui.model.CollabEntry> collabTable(
            java.util.List<com.benjagest.ui.model.CollabEntry> data,
            boolean isIncoming, boolean showAcceptedAt) {
        TableView<com.benjagest.ui.model.CollabEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(isIncoming
                ? t("team.collab.incoming.empty")
                : (showAcceptedAt ? t("team.collab.active.empty") : t("team.collab.outgoing.empty"))));
        table.setPrefHeight(160);

        TableColumn<com.benjagest.ui.model.CollabEntry, String> cEmail =
                new TableColumn<>(t("team.collab.col.email"));
        cEmail.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().invitedEmail()));
        cEmail.setPrefWidth(260);
        TableColumn<com.benjagest.ui.model.CollabEntry, String> cStatus =
                new TableColumn<>(t("team.collab.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeCollabStatus(c.getValue().status())));
        cStatus.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.CollabEntry, String> cDate =
                new TableColumn<>(showAcceptedAt
                        ? t("team.collab.col.accepted_at")
                        : t("team.collab.col.invited_at"));
        cDate.setCellValueFactory(c -> {
            var dt = showAcceptedAt
                    ? c.getValue().acceptedAt()
                    : c.getValue().invitedAt();
            return new SimpleStringProperty(dt == null ? "" : shortIso(dt.toString()));
        });
        cDate.setPrefWidth(150);

        table.getColumns().addAll(List.of(cEmail, cStatus, cDate));
        table.setItems(FXCollections.observableArrayList(data));
        return table;
    }

    private void openInviteCollabDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("team.collab.invite.dialog.title"));
        dialog.setHeaderText(t("team.collab.invite.dialog.header"));

        TextField emailF = new TextField();
        emailF.setPromptText(t("team.collab.invite.dialog.email_prompt"));
        TextArea notesF = new TextArea();
        notesF.setPromptText(t("team.collab.invite.dialog.notes_prompt"));
        notesF.setPrefRowCount(3);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(8));
        g.add(new Label(t("team.collab.invite.dialog.email")), 0, 0);
        g.add(emailF, 1, 0);
        g.add(new Label(t("team.collab.invite.dialog.notes")), 0, 1);
        g.add(notesF, 1, 1);
        Label hint = new Label(t("team.collab.invite.dialog.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        g.add(hint, 0, 2, 2, 1);

        dialog.getDialogPane().setContent(g);
        ButtonType sendBt = new ButtonType(t("team.collab.invite.dialog.send"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(sendBt, ButtonType.CANCEL);
        javafx.scene.Node sendNode = dialog.getDialogPane().lookupButton(sendBt);
        sendNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            String email = emailF.getText() == null ? "" : emailF.getText().trim();
            if (email.isBlank() || !email.contains("@")) {
                showError(t("team.collab.invite.dialog.bad_email.title"),
                        t("team.collab.invite.dialog.bad_email.body"));
                ev.consume();
                return;
            }
            ev.consume();
            String notes = notesF.getText();
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.inviteCollab(email, notes);
                    return null;
                }
            };
            task.setOnSucceeded(s -> {
                dialog.setResult(sendBt);
                dialog.close();
                showInfo(t("team.collab.invite.ok.title"),
                        t("team.collab.invite.ok.body") + " " + email);
                show();
            });
            task.setOnFailed(s -> {
                Throwable err = task.getException();
                showError(t("team.collab.invite.fail.title"),
                        err == null || err.getMessage() == null
                                ? t("team.collab.invite.fail.body")
                                : err.getMessage());
            });
            start(task, "collab-invite");
        });
        dialog.showAndWait();
    }

    private void confirmRevokeCollab(TableView<com.benjagest.ui.model.CollabEntry> table) {
        var sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(t("team.collab.revoke.confirm.title"));
        confirm.setHeaderText(t("team.collab.revoke.confirm.title"));
        confirm.setContentText(t("team.collab.revoke.confirm.body"));
        confirm.showAndWait().ifPresent(rsp -> {
            if (rsp == javafx.scene.control.ButtonType.OK) {
                runCollabAction(() -> altaApiClient.revokeCollab(sel.id()),
                        "team.collab.revoke.ok", "team.collab.revoke.fail");
            }
        });
    }

    /**
     * Helper para ejecutar acciones POST/DELETE de colaboración: corre
     * en Task asíncrono, muestra info/error y recarga el módulo.
     */
    private void runCollabAction(ThrowingRunnable action,
                                   String okKey, String failKey) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                action.run();
                return null;
            }
        };
        task.setOnSucceeded(s -> {
            showInfo(t(okKey + ".title"), t(okKey + ".body"));
            show();
        });
        task.setOnFailed(s -> {
            Throwable err = task.getException();
            showError(t(failKey + ".title"),
                    err == null || err.getMessage() == null
                            ? t(failKey + ".body") : err.getMessage());
        });
        start(task, "collab-action");
    }

    private String humanizeCollabStatus(String code) {
        if (code == null || code.isBlank()) return "";
        String key = "team.collab.status." + code;
        String translated = t(key);
        return key.equals(translated) ? code : translated;
    }

    private Node teamDelegationsTab(TeamBundle bundle) {
        // Slice 5E: editor completo. Botón "Nueva delegación" abre diálogo
        // con combo de asignación + combo sustituto + DatePickers. Botón
        // "Quitar delegación" en la fila seleccionada llama al mismo
        // endpoint con toUserId=null.
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        Label hint = new Label(t("team.deleg.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.TeamAssignment> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("team.deleg.empty")));

        java.util.Map<String, String> nameById = new java.util.HashMap<>();
        for (com.benjagest.ui.model.CustomerPortfolioEntry c : bundle.clients()) {
            if (c.linkedCompanyId() != null) nameById.put(c.linkedCompanyId(), labelOf(c));
        }
        java.util.Map<String, String> memberById = new java.util.HashMap<>();
        for (com.benjagest.ui.model.TeamMember m : bundle.members()) memberById.put(m.userId(), m.label());

        TableColumn<com.benjagest.ui.model.TeamAssignment, String> cClient =
                new TableColumn<>(t("team.deleg.col.client"));
        cClient.setCellValueFactory(c -> new SimpleStringProperty(
                nameById.getOrDefault(c.getValue().clientCompanyId(), c.getValue().clientCompanyId())));
        cClient.setPrefWidth(220);
        TableColumn<com.benjagest.ui.model.TeamAssignment, String> cHolder =
                new TableColumn<>(t("team.deleg.col.holder"));
        cHolder.setCellValueFactory(c -> new SimpleStringProperty(
                memberById.getOrDefault(c.getValue().employeeUserId(), c.getValue().employeeUserId())));
        cHolder.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.TeamAssignment, String> cSub =
                new TableColumn<>(t("team.deleg.col.substitute"));
        cSub.setCellValueFactory(c -> new SimpleStringProperty(
                memberById.getOrDefault(c.getValue().delegatedToUserId(), "")));
        cSub.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.TeamAssignment, String> cFrom =
                new TableColumn<>(t("team.deleg.col.from"));
        cFrom.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().delegatedFrom() == null ? "" : c.getValue().delegatedFrom().toString()));
        TableColumn<com.benjagest.ui.model.TeamAssignment, String> cUntil =
                new TableColumn<>(t("team.deleg.col.until"));
        cUntil.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().delegatedUntil() == null ? "" : c.getValue().delegatedUntil().toString()));
        table.getColumns().addAll(List.of(cClient, cHolder, cSub, cFrom, cUntil));

        // Solo asignaciones con delegación activa
        java.util.List<com.benjagest.ui.model.TeamAssignment> withDeleg = new java.util.ArrayList<>();
        for (com.benjagest.ui.model.TeamAssignment a : bundle.assignments()) {
            if (a.delegatedToUserId() != null && !a.delegatedToUserId().isBlank()) {
                withDeleg.add(a);
            }
        }
        table.setItems(FXCollections.observableArrayList(withDeleg));

        // Botonera: nueva + quitar (deshabilitado si no hay selección)
        Button newBtn = new Button(t("team.deleg.btn.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.getStyleClass().add("primary-button");
        newBtn.setOnAction(ev -> openDelegationDialog(bundle, null));

        Button cancelBtn = new Button(t("team.deleg.btn.cancel"));
        cancelBtn.setGraphic(icon("fas-times"));
        cancelBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener(
                (o, ov, nv) -> cancelBtn.setDisable(nv == null));
        cancelBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("team.deleg.cancel.confirm.title"));
            confirm.setHeaderText(t("team.deleg.cancel.confirm.title"));
            confirm.setContentText(t("team.deleg.cancel.confirm.body"));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Void> cancelTask = new Task<>() {
                        @Override protected Void call() throws Exception {
                            altaApiClient.delegateAssignment(sel.id(), null, null, null);
                            return null;
                        }
                    };
                    cancelTask.setOnSucceeded(s -> show());
                    cancelTask.setOnFailed(s -> showError(t("team.deleg.cancel.fail.title"),
                            t("team.assign.fail.body")));
                    start(cancelTask, "team-delegation-cancel");
                }
            });
        });
        HBox buttonsRow = new HBox(8, newBtn, cancelBtn);
        buttonsRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(hint, buttonsRow, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    /**
     * Slice 5E — Diálogo para crear/editar una delegación. Si {@code
     * preselectedAssignment} no es null, el combo de asignación se
     * pre-rellena con ella (caso: "editar" desde una fila). Si es
     * null, el OWNER elige asignación libremente.
     */
    private void openDelegationDialog(TeamBundle bundle,
                                       com.benjagest.ui.model.TeamAssignment preselectedAssignment) {
        if (bundle.assignments().isEmpty()) {
            showInfo(t("team.deleg.no_assignments.title"),
                    t("team.deleg.no_assignments.body"));
            return;
        }

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(t("team.deleg.dialog.title"));
        dialog.setHeaderText(t("team.deleg.dialog.header"));

        // Mapas para presentación legible
        java.util.Map<String, String> clientNameById = new java.util.HashMap<>();
        for (com.benjagest.ui.model.CustomerPortfolioEntry c : bundle.clients()) {
            if (c.linkedCompanyId() != null) clientNameById.put(c.linkedCompanyId(), labelOf(c));
        }
        java.util.Map<String, String> memberLabelById = new java.util.HashMap<>();
        for (com.benjagest.ui.model.TeamMember m : bundle.members()) memberLabelById.put(m.userId(), m.label());

        // Combo de asignación: muestra "cliente · titular"
        ComboBox<com.benjagest.ui.model.TeamAssignment> assignmentCombo = new ComboBox<>();
        assignmentCombo.getItems().addAll(bundle.assignments());
        assignmentCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.TeamAssignment a) {
                if (a == null) return "";
                String client = clientNameById.getOrDefault(a.clientCompanyId(), a.clientCompanyId());
                String holder = memberLabelById.getOrDefault(a.employeeUserId(), a.employeeUserId());
                return client + " · " + holder;
            }
            @Override public com.benjagest.ui.model.TeamAssignment fromString(String s) { return null; }
        });
        assignmentCombo.setPromptText(t("team.deleg.dialog.assignment_prompt"));
        assignmentCombo.setMaxWidth(Double.MAX_VALUE);
        if (preselectedAssignment != null) {
            assignmentCombo.setValue(preselectedAssignment);
        }

        // Combo de sustituto: todos los miembros, se filtra al elegir
        // asignación para excluir al titular en runtime
        ComboBox<com.benjagest.ui.model.TeamMember> substituteCombo = new ComboBox<>();
        substituteCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.TeamMember m) {
                return m == null ? "" : m.label();
            }
            @Override public com.benjagest.ui.model.TeamMember fromString(String s) { return null; }
        });
        substituteCombo.setPromptText(t("team.deleg.dialog.substitute_prompt"));
        substituteCombo.setMaxWidth(Double.MAX_VALUE);

        Runnable refreshSubstitutes = () -> {
            String holderId = assignmentCombo.getValue() == null
                    ? null : assignmentCombo.getValue().employeeUserId();
            substituteCombo.getItems().clear();
            for (com.benjagest.ui.model.TeamMember m : bundle.members()) {
                if (holderId == null || !holderId.equals(m.userId())) {
                    substituteCombo.getItems().add(m);
                }
            }
        };
        assignmentCombo.valueProperty().addListener((o, ov, nv) -> refreshSubstitutes.run());
        refreshSubstitutes.run();

        // DatePickers — desde hoy hasta hoy+30 por defecto, edita libre
        java.time.LocalDate today = java.time.LocalDate.now();
        DatePicker fromPicker = new DatePicker(today);
        fromPicker.setMaxWidth(Double.MAX_VALUE);
        DatePicker untilPicker = new DatePicker(today.plusDays(7));
        untilPicker.setMaxWidth(Double.MAX_VALUE);

        // Si la asignación seleccionada ya tenía delegación, pre-rellenamos
        if (preselectedAssignment != null && preselectedAssignment.delegatedToUserId() != null) {
            for (com.benjagest.ui.model.TeamMember m : bundle.members()) {
                if (m.userId().equals(preselectedAssignment.delegatedToUserId())) {
                    substituteCombo.setValue(m);
                    break;
                }
            }
            if (preselectedAssignment.delegatedFrom() != null) fromPicker.setValue(preselectedAssignment.delegatedFrom());
            if (preselectedAssignment.delegatedUntil() != null) untilPicker.setValue(preselectedAssignment.delegatedUntil());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(8));
        grid.add(new Label(t("team.deleg.dialog.assignment")), 0, 0);
        grid.add(assignmentCombo, 1, 0);
        grid.add(new Label(t("team.deleg.dialog.substitute")), 0, 1);
        grid.add(substituteCombo, 1, 1);
        grid.add(new Label(t("team.deleg.dialog.from")), 0, 2);
        grid.add(fromPicker, 1, 2);
        grid.add(new Label(t("team.deleg.dialog.until")), 0, 3);
        grid.add(untilPicker, 1, 3);
        javafx.scene.layout.ColumnConstraints col0 = new javafx.scene.layout.ColumnConstraints();
        col0.setMinWidth(120);
        javafx.scene.layout.ColumnConstraints col1 = new javafx.scene.layout.ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        col1.setFillWidth(true);
        grid.getColumnConstraints().addAll(col0, col1);

        dialog.getDialogPane().setContent(grid);
        ButtonType saveBtn = new ButtonType(t("team.deleg.dialog.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        // Interceptamos el OK_DONE para validar antes de cerrar
        final javafx.scene.Node saveNode = dialog.getDialogPane().lookupButton(saveBtn);
        saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            var a = assignmentCombo.getValue();
            var sub = substituteCombo.getValue();
            var from = fromPicker.getValue();
            var until = untilPicker.getValue();
            if (a == null || sub == null || from == null || until == null) {
                showError(t("team.deleg.invalid.title"), t("team.deleg.invalid.body"));
                ev.consume();
                return;
            }
            if (sub.userId().equals(a.employeeUserId())) {
                showError(t("team.deleg.invalid.same.title"), t("team.deleg.invalid.same.body"));
                ev.consume();
                return;
            }
            if (from.isAfter(until)) {
                showError(t("team.deleg.invalid.range.title"), t("team.deleg.invalid.range.body"));
                ev.consume();
                return;
            }
            // Validación pasada: enviar al backend de forma async. NO cerramos
            // todavía — el handle del result hace setOnSucceeded → showTeamModule.
            ev.consume();
            String assignmentId = a.id();
            String subId = sub.userId();
            Task<Void> save = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.delegateAssignment(assignmentId, subId, from, until);
                    return null;
                }
            };
            save.setOnSucceeded(s -> {
                dialog.setResult(true);
                dialog.close();
                showInfo(t("team.deleg.ok.title"), t("team.deleg.ok.body"));
                show();
            });
            save.setOnFailed(s -> showError(t("team.deleg.fail.title"),
                    t("team.assign.fail.body")));
            start(save, "team-delegation-save");
        });

        dialog.showAndWait();
    }

    /**
     * Helper genérico de humanización: si {@code key} tiene traducción
     * la devuelve, si no devuelve {@code fallback}. Usado por los combos
     * de empleado (sexo/estado civil) y otros sitios donde tenemos
     * pares (key, código_bd) que necesitan mostrarse traducidos sin
     * romper si el código es desconocido.
     */
    private String humanizeFromKey(String key, String fallback) {
        if (key == null || key.isBlank()) return fallback == null ? "" : fallback;
        String translated = t(key);
        return key.equals(translated) ? (fallback == null ? "" : fallback) : translated;
    }

    /**
     * Traduce el rol de un miembro en la asesoría (OWNER, ADMIN…) al
     * idioma actual. Si el código no está en el helper i18n cae al
     * propio código en lugar de petar — robusto a roles nuevos.
     */
    private String humanizeMemberRole(String code) {
        if (code == null || code.isBlank()) return "";
        String key = "team.member_role." + code;
        String translated = t(key);
        return key.equals(translated) ? code : translated;
    }

    /**
     * Traduce el rol asignado dentro de un cliente (ADVISOR, ACCOUNTANT…)
     * al idioma actual. Mismo patrón defensivo que humanizeMemberRole.
     */
    private String humanizeAssignmentRole(String code) {
        if (code == null || code.isBlank()) return "";
        String key = "team.role." + code;
        String translated = t(key);
        return key.equals(translated) ? code : translated;
    }
}
