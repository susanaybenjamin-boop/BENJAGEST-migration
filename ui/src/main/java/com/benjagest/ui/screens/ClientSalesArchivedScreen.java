package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.AccountingApiClient;
import com.benjagest.ui.support.Router;
import java.time.LocalDate;
import java.util.function.Function;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * AS-4 — Listado "Ventas (archivadas)" del cliente NO vinculado (modelo
 * A3/Contasol: el asesor archiva asientos source_type=SALES_PDF_IMPORT, no emite
 * facturas). Filtros + banner de incidencias (duplicados / sin nº / descuadres)
 * con sus diálogos accionables + editor de concepto. Extraído del God Object.
 *
 * <p>Lo compartido (importar PDFs, import auto venta/gasto, editor recurrente)
 * se inyecta vía {@link Host}; el shell conserva
 * {@code buildClientSalesArchivedTab(...)} como wrapper (1 caller: el orquestador
 * buildClientSalesAndExpensesTab). Movido tal cual; CSS/i18n sin tocar.
 */
public class ClientSalesArchivedScreen extends ScreenBase {

    /** Acciones compartidas que siguen en el shell. */
    public interface Host {
        void importSalesPdfsMulti();
        void importPdfsAuto(String selfTaxId);
        void openRecurringEditorFromInvoice(String kind, String partyNif, String partyName,
                                            java.math.BigDecimal total, LocalDate invoiceDate);
        /**
         * COB-2 — abre el cuadro de vencimientos (cobro) de la factura de venta
         * enlazada al asiento. Es el MISMO diálogo que usa Facturación en modo
         * empresario; el shell lo conserva porque lo comparten Ventas y Compras.
         */
        void openDueDatesDialog(String kind, String invoiceId, String partyName,
                                java.math.BigDecimal total);
    }

    private final AccountingApiClient accountingApiClient;
    private final Host host;

    public ClientSalesArchivedScreen(AccountingApiClient accountingApiClient,
                                     Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.accountingApiClient = accountingApiClient;
        this.host = host;
    }

    public Node buildTab(
            javafx.beans.property.ObjectProperty<LocalDate> fromProp,
            javafx.beans.property.ObjectProperty<LocalDate> toProp,
            String selfTaxId) {
        javafx.scene.control.TableView<com.benjagest.ui.model.AccountingModels.DiaryEntry> table =
                new javafx.scene.control.TableView<>();
        table.getStyleClass().add("data-table");
        table.setPlaceholder(new Label(t("list.placeholder.empty")));

        // Cache cliente-side del listado COMPLETO del periodo. Los
        // filtros (texto, estado, tipo) se aplican sobre este cache
        // sin recargar del backend.
        final java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry> cache =
                new java.util.ArrayList<>();
        // Set de SHA-256 vistos más de una vez en el cache. Usado por
        // la columna de duplicados y el banner de avisos. Se recalcula
        // cada vez que el cache cambia (recomputeIssues, más abajo).
        final java.util.Set<String> duplicateShas = new java.util.HashSet<>();

        // Slice 3B + acciones — banner de avisos con LINKS clickables.
        // Cada incidencia abre un diálogo con la lista afectada y
        // acciones contextuales (eliminar duplicados, editar nº, etc.).
        javafx.scene.control.Hyperlink dupLink = new javafx.scene.control.Hyperlink();
        javafx.scene.control.Hyperlink missLink = new javafx.scene.control.Hyperlink();
        javafx.scene.control.Hyperlink unbLink = new javafx.scene.control.Hyperlink();
        for (javafx.scene.control.Hyperlink h : new javafx.scene.control.Hyperlink[]{dupLink, missLink, unbLink}) {
            h.setStyle("-fx-text-fill: #6d4c00; -fx-underline: true;");
        }
        Label warningsIcon = new Label("⚠");
        Label sep1 = new Label("  ·  ");
        Label sep2 = new Label("  ·  ");
        sep1.setStyle("-fx-text-fill: #6d4c00;");
        sep2.setStyle("-fx-text-fill: #6d4c00;");

        HBox warningsBanner = new HBox(4, warningsIcon, dupLink, sep1, missLink, sep2, unbLink);
        warningsBanner.setStyle("-fx-background-color: #fff8e1;"
                + "-fx-background-radius: 4;"
                + "-fx-padding: 8 12 8 12;"
                + "-fx-border-color: #f0d56d;"
                + "-fx-border-radius: 4;"
                + "-fx-border-width: 1;");
        warningsBanner.setAlignment(Pos.CENTER_LEFT);
        warningsBanner.setVisible(false);
        warningsBanner.setManaged(false);

        // Click handlers — pasan el cache directamente, el filtro está
        // hecho cliente-side dentro del diálogo.
        dupLink.setOnAction(e -> showDuplicatesDialog(cache, duplicateShas));
        missLink.setOnAction(e -> showMissingNumberDialog(cache));
        unbLink.setOnAction(e -> showUnbalancedDialog(cache));

        // Columna Nº: muestra ÍNDICE DE FILA visual (1, 2, 3, …) según
        // el orden actual del listado, NO el entry_number real del
        // asiento. Esto evita que el asesor vea huecos (1, 3, 4, 9…)
        // cuando se borran asientos por duplicado. El entry_number
        // real se mantiene intacto en BD por trazabilidad contable.
        // Como no representa el asiento real, no es ordenable.
        javafx.scene.control.TableColumn<com.benjagest.ui.model.AccountingModels.DiaryEntry, Void> colNum =
                new javafx.scene.control.TableColumn<>(t("accounting.col.num"));
        colNum.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setText("");
                } else {
                    setText(String.valueOf(getIndex() + 1));
                }
            }
        });
        colNum.setSortable(false);
        colNum.setPrefWidth(60);

        addColSorted(table, t("accounting.col.date"),
                v -> v.entryDate() == null ? "" : v.entryDate().toString(), 110, ISO_DATE_COMPARATOR);
        addCol(table, t("accounting.col.concept"),
                v -> v.concept() == null ? "" : v.concept(), 320);
        addColSorted(table, t("accounting.col.debit_total"),
                v -> v.totalDebit() == null ? "" : v.totalDebit().toString(), 110, NUMERIC_STRING_COMPARATOR);
        addCol(table, t("accounting.col.status"),
                v -> v.status() == null ? "" : t("accounting.status." + v.status()), 110);

        table.getColumns().add(0, colNum);

        // Filtros internos (Slice 2B):
        //   - Buscar: matchea sobre concepto + nº.
        //   - Estado: TODOS | DRAFT (por validar) | POSTED (validado) | VOIDED.
        //   - Tipo:   TODOS | NORMAL | RECTIFICATIVA (total negativo
        //             o concepto con "rectific"/"abono").
        TextField search = new TextField();
        search.setPromptText(t("client.filter.search_prompt"));
        search.setPrefColumnCount(20);

        ComboBox<String> statusFilter = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "", "DRAFT", "POSTED", "VOIDED"));
        statusFilter.setValue("");
        statusFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null || s.isBlank()
                        ? t("client.filter.status.all")
                        : t("accounting.status." + s);
            }
            @Override public String fromString(String s) { return s; }
        });

        ComboBox<String> typeFilter = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "", "NORMAL", "RECT"));
        typeFilter.setValue("");
        typeFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null || s.isBlank() ? t("client.filter.type.all")
                        : "RECT".equals(s) ? t("client.filter.type.rectifying")
                        : t("client.filter.type.normal");
            }
            @Override public String fromString(String s) { return s; }
        });

        Runnable applyFilters = () -> {
            // Recalcular duplicados + métricas para el banner de avisos
            // antes de filtrar. Es barato (O(n) sobre cache de 50-200).
            duplicateShas.clear();
            java.util.Map<String, Integer> shaCount = new java.util.HashMap<>();
            int missingNumber = 0;
            int unbalanced = 0;
            for (var e : cache) {
                if (e.sourcePdfSha256() != null && !e.sourcePdfSha256().isBlank()) {
                    shaCount.merge(e.sourcePdfSha256(), 1, Integer::sum);
                }
                String concept = e.concept() == null ? "" : e.concept();
                boolean noNum = concept.toLowerCase().startsWith("venta importada")
                        || (concept.contains("Fra.")
                                && !concept.matches(".*Fra\\.\\s*[A-Z0-9].*\\d.*"));
                if (noNum) missingNumber++;
                if (e.totalDebit() != null && e.totalCredit() != null) {
                    var diff = e.totalDebit().subtract(e.totalCredit()).abs();
                    if (diff.compareTo(new java.math.BigDecimal("0.01")) > 0) {
                        unbalanced++;
                    }
                }
            }
            int dupCount = 0;
            for (var en : shaCount.entrySet()) {
                if (en.getValue() > 1) {
                    duplicateShas.add(en.getKey());
                    dupCount += en.getValue();
                }
            }
            // Actualizar banner — cada link visible solo si tiene cuenta > 0.
            final int dupCountF = dupCount;
            final int missF = missingNumber;
            final int unbF = unbalanced;
            dupLink.setText(t("client.warnings.duplicates")
                    .replace("{n}", String.valueOf(dupCountF)));
            dupLink.setVisible(dupCountF > 0);
            dupLink.setManaged(dupCountF > 0);
            missLink.setText(t("client.warnings.missing_number")
                    .replace("{n}", String.valueOf(missF)));
            missLink.setVisible(missF > 0);
            missLink.setManaged(missF > 0);
            unbLink.setText(t("client.warnings.unbalanced")
                    .replace("{n}", String.valueOf(unbF)));
            unbLink.setVisible(unbF > 0);
            unbLink.setManaged(unbF > 0);
            // Separadores: solo visibles si flanquean dos links activos.
            sep1.setVisible(dupCountF > 0 && missF > 0);
            sep1.setManaged(sep1.isVisible());
            sep2.setVisible((dupCountF > 0 || missF > 0) && unbF > 0);
            sep2.setManaged(sep2.isVisible());
            boolean anyIssue = dupCountF > 0 || missF > 0 || unbF > 0;
            warningsBanner.setVisible(anyIssue);
            warningsBanner.setManaged(anyIssue);

            // Filtrado de la tabla con los criterios del usuario.
            String q = search.getText() == null ? ""
                    : search.getText().trim().toLowerCase();
            String st = statusFilter.getValue();
            String tp = typeFilter.getValue();
            javafx.collections.ObservableList<com.benjagest.ui.model.AccountingModels.DiaryEntry> filtered =
                    javafx.collections.FXCollections.observableArrayList();
            for (var e : cache) {
                if (!q.isEmpty()) {
                    String haystack =
                            (e.concept() == null ? "" : e.concept().toLowerCase())
                            + " " + e.entryNumber();
                    if (!haystack.contains(q)) continue;
                }
                if (st != null && !st.isBlank()
                        && !st.equalsIgnoreCase(e.status())) continue;
                if (tp != null && !tp.isBlank()) {
                    boolean isRect = isLikelyRectifying(e);
                    if ("RECT".equals(tp) && !isRect) continue;
                    if ("NORMAL".equals(tp) && isRect) continue;
                }
                filtered.add(e);
            }
            table.setItems(filtered);
        };
        search.textProperty().addListener((o, a, b) -> applyFilters.run());
        statusFilter.valueProperty().addListener((o, a, b) -> applyFilters.run());
        typeFilter.valueProperty().addListener((o, a, b) -> applyFilters.run());

        Button refresh = new Button(t("accounting.action.refresh"));
        refresh.setOnAction(e -> loadClientSalesArchived(table, cache,
                fromProp.get(), toProp.get(), applyFilters));

        // Slice 2C: si tenemos el NIF del cliente activo, el botón hace
        // auto-detección por PDF (venta vs gasto comparando NIF emisor).
        // Sin NIF (callsite legacy), se mantiene el comportamiento solo
        // venta.
        Button importBtn = new Button(selfTaxId == null || selfTaxId.isBlank()
                ? t("sales.action.import_pdfs")
                : t("client.action.import_pdfs_auto"));
        importBtn.setGraphic(icon("fas-file-import"));
        importBtn.getStyleClass().add("button-primary");
        if (selfTaxId == null || selfTaxId.isBlank()) {
            importBtn.setOnAction(e -> host.importSalesPdfsMulti());
        } else {
            importBtn.setOnAction(e -> host.importPdfsAuto(selfTaxId));
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // Slice 3T — Botón "Hacer recurrente" para asientos de venta
        // POSTED del cliente NO vinculado. Como aquí ya estamos dentro
        // de la asesoría actuando como cliente shadow, openRecurring
        // EditorFromInvoice redirigirá automáticamente al editor
        // contable nuevo (ACCOUNTING_INCOME).
        Button makeRecurringBtnSales = new Button(t("list.action.make_recurring"));
        makeRecurringBtnSales.setGraphic(icon("fas-arrows-rotate"));
        makeRecurringBtnSales.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean ok = nv != null && "POSTED".equalsIgnoreCase(nv.status());
            makeRecurringBtnSales.setDisable(!ok);
        });
        makeRecurringBtnSales.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            host.openRecurringEditorFromInvoice(
                    "SALES_INVOICE",
                    null,
                    sel.concept(), // tampoco hay legalName, usamos concept como hint
                    sel.totalDebit(),
                    sel.entryDate());
        });

        // COB-2 — Cobrar la venta validada. Hasta ahora el asesor podía importar
        // la factura y validarla, pero no cobrarla: tenía que irse a Facturación.
        // El asiento importado por PDF queda enlazado a su factura
        // (source_type=SALES_INVOICE + source_id), así que reutilizamos el mismo
        // cuadro de vencimientos del modo empresario sin duplicar nada.
        //
        // Solo se habilita si HAY factura enlazada: una venta metida como asiento
        // manual no tiene fila en sales_invoices y por tanto no puede tener cuadro
        // de vencimientos (decisión Benjamin 2026-09-05). En ese caso el tooltip
        // explica por qué está apagado en lugar de dejar al asesor adivinando.
        Button collectBtn = new Button(t("duedates.action.open_sales"));
        collectBtn.setGraphic(icon("fas-calendar-check"));
        collectBtn.setDisable(true);
        collectBtn.setTooltip(new Tooltip(t("duedates.hint.select")));
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean posted = nv != null && "POSTED".equalsIgnoreCase(nv.status());
            boolean linked = nv != null && "SALES_INVOICE".equals(nv.sourceType())
                    && nv.sourceId() != null && !nv.sourceId().isBlank();
            collectBtn.setDisable(!(posted && linked));
            collectBtn.setTooltip(new Tooltip(
                    posted && !linked ? t("duedates.hint.no_invoice")
                            : t("duedates.hint.select")));
        });
        collectBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null || sel.sourceId() == null || sel.sourceId().isBlank()) return;
            // El listado archivado no trae el nombre del cliente (son asientos):
            // el concepto es el mejor rótulo disponible para el título del diálogo.
            host.openDueDatesDialog("SALES", sel.sourceId(), sel.concept(), sel.totalDebit());
        });

        HBox filtersRow = new HBox(8,
                new Label(t("client.filter.search")), search,
                new Label(t("client.filter.status")), statusFilter,
                new Label(t("client.filter.type")), typeFilter,
                spacer, collectBtn, makeRecurringBtnSales, refresh, importBtn);
        filtersRow.setAlignment(Pos.CENTER_LEFT);

        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                () -> loadClientSalesArchived(table, cache,
                        fromProp.get(), toProp.get(), applyFilters), table);
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_SALES,
                () -> loadClientSalesArchived(table, cache,
                        fromProp.get(), toProp.get(), applyFilters), table);

        // Cuando cambia el periodo de los KPIs, recargamos.
        fromProp.addListener((o, a, b) -> loadClientSalesArchived(
                table, cache, fromProp.get(), toProp.get(), applyFilters));
        toProp.addListener((o, a, b) -> loadClientSalesArchived(
                table, cache, fromProp.get(), toProp.get(), applyFilters));

        // Slice 3A — columna "Dup" que marca con punto rojo las filas
        // cuyo SHA-256 aparece 2+ veces en el cache (= mismo PDF
        // importado varias veces). Se inserta como segunda columna
        // (tras Nº).
        //
        // Antes la cabecera era "⚠" (Unicode WARNING SIGN U+26A0), pero
        // en muchas fuentes de Windows se renderiza como un triángulo
        // SIN color de relleno → aparece como un "triángulo blanco"
        // pegado a la columna Nº y confunde al asesor. Por eso
        // pasamos a una cabecera textual i18n.
        javafx.scene.control.TableColumn<com.benjagest.ui.model.AccountingModels.DiaryEntry, String> colWarn =
                new javafx.scene.control.TableColumn<>(t("accounting.col.dup"));
        colWarn.setCellValueFactory(c -> {
            var e = c.getValue();
            return new javafx.beans.property.SimpleStringProperty(
                    e != null && e.sourcePdfSha256() != null
                            && duplicateShas.contains(e.sourcePdfSha256())
                            ? "●" : "");
        });
        // Color rojo de la paleta del app.css (#dc2626 = status-dot-error)
        // en hex literal — no usamos lookup CSS porque la columna se
        // crea programáticamente y JavaFX no resuelve variables
        // declaradas en .root cuando el estilo es inline.
        colWarn.setStyle("-fx-alignment: CENTER; -fx-text-fill: #dc2626; -fx-font-weight: bold;");
        colWarn.setPrefWidth(44);
        colWarn.setSortable(false);
        table.getColumns().add(1, colWarn);

        VBox box = new VBox(8, warningsBanner, filtersRow, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        loadClientSalesArchived(table, cache, fromProp.get(), toProp.get(),
                applyFilters);
        return box;
    }

    /**
     * Diálogo accionable para resolver duplicados. Agrupa los asientos
     * del cache por SHA, muestra cada grupo + acciones:
     *  - Por grupo: "Conservar el primero, eliminar el resto" (1 click).
     *  - "Resolver TODOS (conservar el más antiguo de cada grupo)" para
     *    barrer la lista de un golpe.
     *
     * Backend: POST /api/accounting/duplicates/delete con ids a borrar.
     */
    private void showDuplicatesDialog(
            java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry> cache,
            java.util.Set<String> duplicateShas) {
        // Agrupar por SHA solo los duplicados.
        java.util.LinkedHashMap<String, java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry>> groups =
                new java.util.LinkedHashMap<>();
        for (var e : cache) {
            String sha = e.sourcePdfSha256();
            if (sha != null && duplicateShas.contains(sha)) {
                groups.computeIfAbsent(sha, k -> new java.util.ArrayList<>()).add(e);
            }
        }
        // Ordenar cada grupo por entryNumber asc — el primero es el "más
        // antiguo/canónico"; los siguientes son las copias a eliminar.
        for (var list : groups.values()) {
            list.sort(java.util.Comparator.comparingInt(
                    com.benjagest.ui.model.AccountingModels.DiaryEntry::entryNumber));
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("client.duplicates.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox container = new VBox(10);
        container.setPadding(new Insets(12));

        Label hint = new Label(t("client.duplicates.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        container.getChildren().add(hint);

        // Mapa entryId → RadioButton para construir la lista de
        // "qué conservar" cuando el usuario pulsa Aplicar.
        java.util.Map<String, javafx.scene.control.RadioButton> keepRadios =
                new java.util.LinkedHashMap<>();
        // Mapa grupo → todos los entryIds del grupo, para sacar el
        // complemento al "kept".
        java.util.List<java.util.List<String>> groupIdLists = new java.util.ArrayList<>();

        Button applyBtn = new Button();
        applyBtn.getStyleClass().add("button-primary");

        // Por cada grupo, un bloque con radios "Conservar" + datos.
        for (var entry : groups.entrySet()) {
            var list = entry.getValue();
            VBox groupBox = new VBox(4);
            groupBox.setStyle("-fx-background-color: #f7f7f7;"
                    + "-fx-padding: 8 10 8 10;"
                    + "-fx-background-radius: 4;");
            Label groupTitle = new Label(t("client.duplicates.group")
                    .replace("{n}", String.valueOf(list.size()))
                    .replace("{sha}", entry.getKey().substring(0,
                            Math.min(12, entry.getKey().length())) + "…"));
            groupTitle.setStyle("-fx-font-weight: bold;");
            groupBox.getChildren().add(groupTitle);

            javafx.scene.control.ToggleGroup tg = new javafx.scene.control.ToggleGroup();
            java.util.List<String> idsThisGroup = new java.util.ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                var dEntry = list.get(i);
                javafx.scene.control.RadioButton radio =
                        new javafx.scene.control.RadioButton();
                radio.setToggleGroup(tg);
                // Por defecto seleccionamos el PRIMERO de cada grupo
                // (el más antiguo por entryNumber, que suele ser el
                // canónico). El usuario puede cambiarlo a otro.
                if (i == 0) radio.setSelected(true);

                Label info = new Label(
                        "Nº " + (dEntry.entryNumber() <= 0 ? "—" : dEntry.entryNumber())
                        + "  ·  " + (dEntry.entryDate() == null ? "" : dEntry.entryDate())
                        + "  ·  " + (dEntry.concept() == null ? "" :
                                (dEntry.concept().length() > 60
                                        ? dEntry.concept().substring(0, 60) + "…"
                                        : dEntry.concept()))
                        + "  ·  " + formatMoney(dEntry.totalDebit()));
                HBox row = new HBox(8, radio, info);
                row.setAlignment(Pos.CENTER_LEFT);
                groupBox.getChildren().add(row);

                keepRadios.put(dEntry.id(), radio);
                idsThisGroup.add(dEntry.id());
            }
            groupIdLists.add(idsThisGroup);
            container.getChildren().add(groupBox);
        }

        // Recalcular el contador del botón cada vez que el usuario
        // cambia un radio. Total a borrar = (totalCopias) - (1 kept
        // por grupo). Como cada ToggleGroup garantiza exactamente 1
        // seleccionado, es simple: sum(group.size()-1) por grupo.
        Runnable updateApplyButton = () -> {
            int toDelete = 0;
            for (var grp : groupIdLists) toDelete += grp.size() - 1;
            applyBtn.setText(t("client.duplicates.apply")
                    .replace("{n}", String.valueOf(toDelete)));
            applyBtn.setDisable(toDelete <= 0);
        };
        for (var r : keepRadios.values()) {
            r.selectedProperty().addListener((o, a, b) -> updateApplyButton.run());
        }
        updateApplyButton.run();

        if (groupIdLists.isEmpty()) {
            Label empty = new Label(t("client.duplicates.none"));
            empty.getStyleClass().add("settings-hint");
            container.getChildren().add(empty);
        } else {
            // Atajos: conservar el más antiguo / más reciente de cada
            // grupo de un click. Útiles si el asesor tiene 10 grupos
            // y todos tienen el mismo criterio.
            Button keepOldestBtn = new Button(t("client.duplicates.keep_oldest"));
            keepOldestBtn.setOnAction(e -> {
                for (var grp : groupIdLists) {
                    keepRadios.get(grp.get(0)).setSelected(true);
                }
            });
            Button keepNewestBtn = new Button(t("client.duplicates.keep_newest"));
            keepNewestBtn.setOnAction(e -> {
                for (var grp : groupIdLists) {
                    keepRadios.get(grp.get(grp.size() - 1)).setSelected(true);
                }
            });

            applyBtn.setOnAction(e -> {
                // Calcular ids a eliminar = TODOS los del grupo menos
                // el "kept" (radio seleccionado).
                java.util.List<String> idsToDelete = new java.util.ArrayList<>();
                for (var grp : groupIdLists) {
                    for (String id : grp) {
                        if (!keepRadios.get(id).isSelected()) idsToDelete.add(id);
                    }
                }
                if (idsToDelete.isEmpty()) return;
                applyBtn.setDisable(true);
                Task<Integer> task = new Task<>() {
                    @Override protected Integer call() throws Exception {
                        return accountingApiClient.deleteImportedEntries(idsToDelete);
                    }
                };
                task.setOnSucceeded(ev -> {
                    showInfo(t("client.duplicates.done.title"),
                            t("client.duplicates.done.body")
                                    .replace("{n}", String.valueOf(task.getValue())));
                    com.benjagest.ui.support.RefreshBus.emit(
                            com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                            com.benjagest.ui.support.RefreshBus.TOPIC_SALES);
                    dialog.setResult(ButtonType.CLOSE);
                    dialog.close();
                });
                task.setOnFailed(ev -> {
                    applyBtn.setDisable(false);
                    Throwable ex = task.getException();
                    showError(t("client.duplicates.fail.title"),
                            ex == null ? "" : ex.getMessage());
                });
                start(task, "delete-duplicates");
            });

            Region actionsSpacer = new Region();
            HBox.setHgrow(actionsSpacer, Priority.ALWAYS);
            HBox actions = new HBox(8, keepOldestBtn, keepNewestBtn,
                    actionsSpacer, applyBtn);
            actions.setAlignment(Pos.CENTER_LEFT);
            container.getChildren().add(actions);
        }

        ScrollPane scroll = new ScrollPane(container);
        scroll.setFitToWidth(true);
        installDialog(dialog, scroll);
        dialog.getDialogPane().setPrefSize(900, 600);
        dialog.showAndWait();
    }

    /**
     * Diálogo de "asientos sin nº de factura". Por ahora solo lista —
     * el botón "Editar" llama a {@link #openEntryEditorInDiary} para
     * que el asesor edite el concepto del asiento. La re-extracción
     * con PDF viene en un slice futuro.
     */
    private void showMissingNumberDialog(
            java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry> cache) {
        java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry> affected = new java.util.ArrayList<>();
        for (var e : cache) {
            String c = e.concept() == null ? "" : e.concept();
            boolean noNum = c.toLowerCase().startsWith("venta importada")
                    || (c.contains("Fra.")
                            && !c.matches(".*Fra\\.\\s*[A-Z0-9].*\\d.*"));
            if (noNum) affected.add(e);
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("client.missing.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        Label hint = new Label(t("client.missing.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        box.getChildren().add(hint);
        if (affected.isEmpty()) {
            Label empty = new Label(t("client.missing.none"));
            empty.getStyleClass().add("settings-hint");
            box.getChildren().add(empty);
        }
        for (var e : affected) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setStyle("-fx-background-color: #fafafa; -fx-padding: 6 10 6 10;"
                    + "-fx-background-radius: 4;");
            Label desc = new Label(
                    "Nº " + (e.entryNumber() <= 0 ? "—" : e.entryNumber())
                    + "  ·  " + (e.entryDate() == null ? "" : e.entryDate())
                    + "  ·  " + (e.concept() == null ? "" :
                            (e.concept().length() > 80
                                    ? e.concept().substring(0, 80) + "…"
                                    : e.concept()))
                    + "  ·  " + formatMoney(e.totalDebit()));
            HBox.setHgrow(desc, Priority.ALWAYS);
            Button openBtn = new Button(t("client.missing.action.edit"));
            openBtn.setOnAction(ev -> showEditConceptDialog(e));
            row.getChildren().addAll(desc, openBtn);
            box.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        installDialog(dialog, scroll);
        dialog.getDialogPane().setPrefSize(900, 600);
        dialog.showAndWait();
    }

    /** Diálogo de descuadres — solo lista por ahora. */
    private void showUnbalancedDialog(
            java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry> cache) {
        java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry> affected = new java.util.ArrayList<>();
        for (var e : cache) {
            if (e.totalDebit() == null || e.totalCredit() == null) continue;
            var diff = e.totalDebit().subtract(e.totalCredit()).abs();
            if (diff.compareTo(new java.math.BigDecimal("0.01")) > 0) affected.add(e);
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("client.unbalanced.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox box = new VBox(6);
        box.setPadding(new Insets(12));
        Label hint = new Label(t("client.unbalanced.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        box.getChildren().add(hint);
        for (var e : affected) {
            Label row = new Label(
                    "Nº " + (e.entryNumber() <= 0 ? "—" : e.entryNumber())
                    + "  ·  " + (e.entryDate() == null ? "" : e.entryDate())
                    + "  ·  D=" + formatMoney(e.totalDebit())
                    + "  ·  H=" + formatMoney(e.totalCredit())
                    + "  ·  Δ=" + formatMoney(
                            e.totalDebit().subtract(e.totalCredit())));
            row.setStyle("-fx-background-color: #fafafa; -fx-padding: 6 10 6 10;"
                    + "-fx-background-radius: 4;");
            box.getChildren().add(row);
        }
        if (affected.isEmpty()) {
            Label empty = new Label(t("client.unbalanced.none"));
            empty.getStyleClass().add("settings-hint");
            box.getChildren().add(empty);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        installDialog(dialog, scroll);
        dialog.getDialogPane().setPrefSize(900, 500);
        dialog.showAndWait();
    }

    /**
     * Diálogo para editar el concepto de un asiento. Incluye botón
     * "Re-extraer del PDF" que usa la regex/heurística actual sobre
     * el PDF asociado y propone el nº de factura nuevo. El asesor
     * decide aceptar la propuesta o escribir manualmente.
     */
    private void showEditConceptDialog(
            com.benjagest.ui.model.AccountingModels.DiaryEntry entry) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("client.edit_concept.title"));
        ButtonType saveBt = new ButtonType(t("client.edit_concept.save"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBt = new ButtonType(t("client.edit_concept.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, cancelBt);

        VBox body = new VBox(10);
        body.setPadding(new Insets(12));

        Label header = new Label(
                "Nº " + (entry.entryNumber() <= 0 ? "—" : entry.entryNumber())
                + "  ·  " + (entry.entryDate() == null ? "" : entry.entryDate()));
        header.setStyle("-fx-font-weight: bold;");

        Label hint = new Label(t("client.edit_concept.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TextField conceptField = new TextField(entry.concept() == null ? "" : entry.concept());
        conceptField.setPrefColumnCount(60);

        Label suggestionLabel = new Label("");
        suggestionLabel.setWrapText(true);
        suggestionLabel.setStyle("-fx-text-fill: #2e7d32;");
        suggestionLabel.setVisible(false);
        suggestionLabel.setManaged(false);

        Button reExtractBtn = new Button(t("client.edit_concept.re_extract"));
        reExtractBtn.setGraphic(icon("fas-magic"));
        reExtractBtn.setOnAction(e -> {
            reExtractBtn.setDisable(true);
            suggestionLabel.setVisible(false);
            suggestionLabel.setManaged(false);
            Task<java.util.Map<String, String>> task = new Task<>() {
                @Override protected java.util.Map<String, String> call() throws Exception {
                    return accountingApiClient.reExtractEntryFromPdf(entry.id());
                }
            };
            task.setOnSucceeded(ev -> {
                reExtractBtn.setDisable(false);
                var data = task.getValue();
                String num = data.getOrDefault("invoiceNumber", "");
                String name = data.getOrDefault("receiverName", "");
                if (num.isBlank() && name.isBlank()) {
                    suggestionLabel.setText(t("client.edit_concept.no_suggestion"));
                    suggestionLabel.setStyle("-fx-text-fill: #c62828;");
                } else {
                    // Construir la propuesta con el formato del backend.
                    StringBuilder proposed = new StringBuilder("Fra. ");
                    if (!num.isBlank()) proposed.append(num).append(' ');
                    if (!name.isBlank()) proposed.append("a ").append(name);
                    suggestionLabel.setText(
                            t("client.edit_concept.suggestion") + "  " + proposed);
                    suggestionLabel.setStyle("-fx-text-fill: #2e7d32;");
                    // Botón "Usar esta sugerencia" inline en el label
                    // via click handler — simple click rellena el campo.
                    suggestionLabel.setOnMouseClicked(mc -> conceptField.setText(
                            proposed.toString().trim()));
                    suggestionLabel.setStyle(suggestionLabel.getStyle()
                            + " -fx-cursor: hand; -fx-underline: true;");
                }
                suggestionLabel.setVisible(true);
                suggestionLabel.setManaged(true);
            });
            task.setOnFailed(ev -> {
                reExtractBtn.setDisable(false);
                Throwable ex = task.getException();
                showError(t("client.edit_concept.re_extract_fail.title"),
                        ex == null ? "" : ex.getMessage());
            });
            start(task, "re-extract");
        });

        body.getChildren().addAll(header, hint,
                new Label(t("client.edit_concept.concept_label")), conceptField,
                reExtractBtn, suggestionLabel);

        installDialog(dialog, body);
        dialog.getDialogPane().setPrefWidth(600);

        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveBt);
        saveBtn.getStyleClass().add("button-primary");
        saveBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            String newConcept = conceptField.getText() == null ? "" : conceptField.getText().trim();
            saveBtn.setDisable(true);
            Task<Boolean> task = new Task<>() {
                @Override protected Boolean call() throws Exception {
                    return accountingApiClient.updateEntryConcept(entry.id(), newConcept);
                }
            };
            task.setOnSucceeded(e -> {
                if (task.getValue()) {
                    com.benjagest.ui.support.RefreshBus.emit(
                            com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
                    dialog.setResult(saveBt);
                    dialog.close();
                } else {
                    saveBtn.setDisable(false);
                    showError(t("client.edit_concept.save_fail.title"),
                            t("client.edit_concept.save_fail.body"));
                }
            });
            task.setOnFailed(e -> {
                saveBtn.setDisable(false);
                Throwable ex = task.getException();
                showError(t("client.edit_concept.save_fail.title"),
                        ex == null ? "" : ex.getMessage());
            });
            start(task, "update-concept");
        });

        dialog.showAndWait();
    }

    /**
     * Heurística rápida cliente-side para distinguir rectificativa de
     * factura normal sin tocar el backend:
     *   - Total negativo (debe o haber) → rectificativa
     *   - Concepto contiene "rectific" / "abono" / "anula" → rectificativa
     */
    private boolean isLikelyRectifying(com.benjagest.ui.model.AccountingModels.DiaryEntry e) {
        if (e == null) return false;
        if (e.totalDebit() != null && e.totalDebit().signum() < 0) return true;
        if (e.totalCredit() != null && e.totalCredit().signum() < 0) return true;
        String concept = e.concept() == null ? "" : e.concept().toLowerCase();
        if (concept.contains("rectific") || concept.contains("abono")
                || concept.contains("anula")) return true;
        return false;
    }

    /**
     * Carga las ventas archivadas (asientos con
     * {@code source_type='SALES_PDF_IMPORT'}) del cliente activo via
     * actingForCompanyId. Filtra por rango de fechas (periodo activo
     * desde el selector trimestre/año de los KPIs).
     */
    private void loadClientSalesArchived(
            javafx.scene.control.TableView<com.benjagest.ui.model.AccountingModels.DiaryEntry> table,
            java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry> cache,
            LocalDate from, LocalDate to,
            Runnable applyFilters) {
        Task<java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.AccountingModels.DiaryEntry> call()
                    throws Exception {
                // Sin filtro de status: DRAFT + POSTED juntos. El periodo
                // viene del selector de arriba (compartido con KPIs).
                // Slice 3U — Listado centralizado de TODAS las ventas
                // del cliente: facturas emitidas/validadas en BENJAGEST
                // (SALES_INVOICE), facturas importadas por PDF
                // (SALES_PDF_IMPORT) y plantillas contables ejecutadas
                // por el cron (RECURRING_ACCOUNTING). Todas se ven en
                // el mismo listado — el asesor no tiene que saltar de
                // pantalla según el origen.
                return accountingApiClient.diary(from, to, null,
                        // Slice 3X — Las recurrentes ya guardan con
                        // source_type SALES_INVOICE; no hace falta
                        // RECURRING_ACCOUNTING aquí.
                        "SALES_INVOICE,SALES_PDF_IMPORT", 500);
            }
        };
        task.setOnSucceeded(ev -> {
            cache.clear();
            cache.addAll(task.getValue());
            applyFilters.run();
        });
        task.setOnFailed(ev -> System.err.println("[client-sales-archived] "
                + (task.getException() == null ? "?" : task.getException().getMessage())));
        start(task, "client-sales-archived");
    }

    // ----- helpers locales (copias stateless del shell) -----

    private <T> void addCol(javafx.scene.control.TableView<T> table, String header,
                              java.util.function.Function<T, String> getter, double width) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    private <T> void addColSorted(javafx.scene.control.TableView<T> table, String header,
                                    java.util.function.Function<T, String> getter, double width,
                                    java.util.Comparator<String> comparator) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        c.setComparator(comparator);
        table.getColumns().add(c);
    }

    private String formatMoney(java.math.BigDecimal v) {
        if (v == null) return "—";
        // Formato es-ES: 1.234,56 €
        return String.format(java.util.Locale.forLanguageTag("es-ES"),
                "%,.2f €", v);
    }
}
