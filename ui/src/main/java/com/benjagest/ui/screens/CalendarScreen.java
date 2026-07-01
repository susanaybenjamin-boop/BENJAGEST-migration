package com.benjagest.ui.screens;

import com.benjagest.ui.model.Language;
import com.benjagest.ui.model.ModuleData;
import com.benjagest.ui.model.ModuleRow;
import com.benjagest.ui.service.WorkspaceApiClient;
import com.benjagest.ui.support.Router;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

/**
 * SM-1 — Módulo Calendario (bloque UIR). Vistas día/semana/mes/año + agenda +
 * diálogos de día/mes, extraídas del God Object (movimiento puro: mismas claves
 * i18n y CSS, mismo comportamiento). El formulario de crear/editar evento
 * (showFormDialog, genérico de módulos) y el icono de módulo (moduleIcon, que
 * depende del catálogo de módulos activos del shell) permanecen en el shell y
 * llegan por {@link Host}. El borrado usa el WorkspaceApiClient genérico y
 * refresca vía navigateTo("calendar"). humanizeCalendarEventType y stripDiacritics
 * se heredan de ScreenBase.
 */
public class CalendarScreen extends ScreenBase {

    /** Puente hacia el shell para el formulario de evento y el icono de módulo. */
    public interface Host {
        void showFormDialog(String module, ModuleRow record);
        void showFormDialog(String module, ModuleRow record, Map<String, String> defaults);
        String moduleIcon(String module);
    }

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final WorkspaceApiClient apiClient;
    private final Language language;
    private final Host host;

    public CalendarScreen(WorkspaceApiClient apiClient, Language language,
                          Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.apiClient = apiClient;
        this.language = language;
        this.host = host;
    }

    // Delegados al shell — mismas firmas que el código movido, para no tocarlo.
    private void showFormDialog(String module, ModuleRow record) { host.showFormDialog(module, record); }
    private void showFormDialog(String module, ModuleRow record, Map<String, String> defaults) { host.showFormDialog(module, record, defaults); }
    private String moduleIcon(String module) { return host.moduleIcon(module); }

    // ==== cuerpo movido del monolito (SM-1) ====

    public VBox calendarView(ModuleData data) {
        VBox content = content();
        LocalDate today = LocalDate.now();

        // Auto-refresh: tras sincronizar con Google Calendar (pull de eventos),
        // la agenda se recarga sola. La suscripción se quita al desmontar.
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_CALENDAR,
                () -> showModule("calendar"), content);

        Label title = new Label(data.title());
        title.getStyleClass().add("module-detail-title");
        Label count = new Label(pluralEvents(data.records().size()));
        count.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, count);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button create = new Button(t("calendar.btn.new_event"));
        create.setGraphic(icon("fas-calendar-plus"));
        create.setOnAction(event -> showFormDialog(data.module(), null));

        HBox header = new HBox(16, titleBox, iconBubble(moduleIcon(data.module()), "module-title-icon"), spacer, create);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        StackPane viewHost = new StackPane();
        viewHost.getStyleClass().add("calendar-view-host");

        // Las claves "day"/"week"/"month"/"year" son estables; el texto
        // visible se traduce y se guarda en userData para identificar
        // botones sin depender del idioma activo.
        List<Button> modeButtons = new ArrayList<>();
        Button dayButton = viewMode("day", false);
        Button weekButton = viewMode("week", false);
        Button monthButton = viewMode("month", true);
        Button yearButton = viewMode("year", false);
        modeButtons.addAll(List.of(dayButton, weekButton, monthButton, yearButton));

        // Navegación: la fecha mostrada (anchor) y el modo viven en holders locales
        // para que las flechas / "Hoy" cambien el periodo y se repinte. Al re-entrar
        // al módulo se reinicia al mes actual.
        final java.time.LocalDate[] anchor = { today };
        final String[] mode = { "month" };
        Runnable render = () -> showCalendarMode(mode[0], data, anchor[0], modeButtons, viewHost);

        dayButton.setOnAction(event -> { mode[0] = "day"; render.run(); });
        weekButton.setOnAction(event -> { mode[0] = "week"; render.run(); });
        monthButton.setOnAction(event -> { mode[0] = "month"; render.run(); });
        yearButton.setOnAction(event -> { mode[0] = "year"; render.run(); });

        Button prev = new Button();
        prev.setGraphic(icon("fas-chevron-left"));
        prev.getStyleClass().add("calendar-mode");
        prev.setOnAction(event -> { anchor[0] = shiftCalendar(anchor[0], mode[0], -1); render.run(); });
        Button todayBtn = new Button(t("calendar.btn.today"));
        todayBtn.getStyleClass().add("calendar-mode");
        todayBtn.setOnAction(event -> { anchor[0] = java.time.LocalDate.now(); render.run(); });
        Button next = new Button();
        next.setGraphic(icon("fas-chevron-right"));
        next.getStyleClass().add("calendar-mode");
        next.setOnAction(event -> { anchor[0] = shiftCalendar(anchor[0], mode[0], 1); render.run(); });

        Region modeSpacer = new Region();
        HBox.setHgrow(modeSpacer, Priority.ALWAYS);
        HBox modes = new HBox(8, prev, todayBtn, next, modeSpacer,
                dayButton, weekButton, monthButton, yearButton);
        modes.getStyleClass().add("calendar-modes");
        modes.setAlignment(Pos.CENTER_LEFT);

        render.run();

        content.getChildren().addAll(header, modes, viewHost);
        return content;
    }

    /** Desplaza la fecha ancla del calendario según el modo (día/semana/mes/año). */
    private java.time.LocalDate shiftCalendar(java.time.LocalDate d, String mode, int delta) {
        return switch (mode) {
            case "day" -> d.plusDays(delta);
            case "week" -> d.plusWeeks(delta);
            case "year" -> d.plusYears(delta);
            default -> d.plusMonths(delta);
        };
    }

    private void showCalendarMode(String modeKey, ModuleData data, LocalDate today, List<Button> buttons, StackPane viewHost) {
        buttons.forEach(button -> button.getStyleClass().remove("calendar-mode-selected"));
        buttons.stream()
                .filter(button -> modeKey.equals(button.getUserData()))
                .findFirst()
                .ifPresent(button -> button.getStyleClass().add("calendar-mode-selected"));

        Node view = switch (modeKey) {
            case "day" -> dayCalendarView(data, today);
            case "week" -> weekCalendarView(data, today);
            case "year" -> yearCalendarView(data, today);
            default -> monthCalendarView(data, today);
        };
        viewHost.getChildren().setAll(view);
    }

    private Button viewMode(String modeKey, boolean selected) {
        Button button = new Button(t("calendar.mode." + modeKey));
        button.setUserData(modeKey);
        button.getStyleClass().add("calendar-mode");
        if (selected) {
            button.getStyleClass().add("calendar-mode-selected");
        }
        return button;
    }

    /** Pluraliza "X eventos" según el idioma activo (sin gramática
     *  compleja: cero/uno/muchos). */
    private String pluralEvents(int count) {
        if (count == 0) return t("calendar.events_count_zero");
        if (count == 1) return t("calendar.events_count_one");
        return count + t("calendar.events_count_many");
    }

    /** Letras de los 7 días de la semana en el idioma activo (L→D / M→S). */
    private String[] localizedWeekdayLetters() {
        return new String[] {
                t("calendar.weekday.mon"), t("calendar.weekday.tue"), t("calendar.weekday.wed"),
                t("calendar.weekday.thu"), t("calendar.weekday.fri"), t("calendar.weekday.sat"),
                t("calendar.weekday.sun")
        };
    }

    /** Locale activo para los nombres largos/cortos de mes/día. */
    private Locale activeLocale() {
        return language == Language.EN ? Locale.ENGLISH : Locale.forLanguageTag("es-ES");
    }

    private HBox monthCalendarView(ModuleData data, LocalDate today) {
        HBox calendarBody = new HBox(14, monthCalendar(data, today), dayAgenda(data, today));
        HBox.setHgrow(calendarBody.getChildren().getFirst(), Priority.ALWAYS);
        return calendarBody;
    }

    private HBox dayCalendarView(ModuleData data, LocalDate today) {
        HBox body = new HBox(14, dayFocusPanel(data, today), dayAgenda(data, today));
        HBox.setHgrow(body.getChildren().getFirst(), Priority.ALWAYS);
        return body;
    }

    private VBox dayFocusPanel(ModuleData data, LocalDate date) {
        List<ModuleRow> events = eventsForDate(data, date);
        VBox panel = new VBox(14);
        panel.getStyleClass().add("calendar-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);

        Label eyebrow = label(date.getDayOfWeek().getDisplayName(TextStyle.FULL, activeLocale()), "eyebrow");
        Label title = label(date.format(DISPLAY_DATE), "calendar-month");
        Label count = label(events.size() == 1
                ? t("calendar.day.scheduled_one")
                : events.size() + t("calendar.day.scheduled_many_suffix"), "section-subtitle");
        panel.getChildren().addAll(eyebrow, title, count);

        if (events.isEmpty()) {
            panel.getChildren().add(label(t("calendar.day.empty"), "status-detail"));
            return panel;
        }

        for (ModuleRow event : events) {
            panel.getChildren().add(calendarEventLine(event));
        }
        return panel;
    }

    private VBox weekCalendarView(ModuleData data, LocalDate today) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        VBox panel = new VBox(14);
        panel.getStyleClass().add("calendar-panel");

        Label title = label(t("calendar.week.range_prefix") + monday.format(DISPLAY_DATE)
                + t("calendar.week.range_middle") + monday.plusDays(6).format(DISPLAY_DATE), "calendar-month");
        TilePane week = new TilePane();
        week.getStyleClass().add("week-grid");
        week.setHgap(10);
        week.setVgap(10);
        week.setPrefTileWidth(190);
        week.setPrefTileHeight(190);

        for (int index = 0; index < 7; index++) {
            LocalDate date = monday.plusDays(index);
            week.getChildren().add(weekDayPanel(data, date, date.equals(today)));
        }

        panel.getChildren().addAll(title, week);
        return panel;
    }

    private VBox weekDayPanel(ModuleData data, LocalDate date, boolean today) {
        List<ModuleRow> events = eventsForDate(data, date);
        VBox day = new VBox(8);
        day.getStyleClass().add("week-day");
        if (today) {
            day.getStyleClass().add("week-day-today");
        }
        day.getChildren().addAll(
                label(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, activeLocale()), "calendar-weekday"),
                label(date.getDayOfMonth() + "/" + date.getMonthValue(), "calendar-day-number")
        );
        if (events.isEmpty()) {
            day.getChildren().add(label(t("calendar.week.no_events"), "status-detail"));
            return day;
        }
        events.stream().limit(3).forEach(event -> day.getChildren().add(calendarEventChip(event)));
        if (events.size() > 3) {
            day.getChildren().add(label(t("calendar.week.more_prefix") + (events.size() - 3) + t("calendar.week.more_suffix"), "calendar-event-badge"));
        }
        return day;
    }

    private VBox yearCalendarView(ModuleData data, LocalDate today) {
        VBox panel = new VBox(14);
        panel.getStyleClass().add("calendar-panel");

        Label title = label(t("calendar.year.title_prefix") + today.getYear(), "calendar-month");
        TilePane months = new TilePane();
        months.getStyleClass().add("year-grid");
        months.setHgap(12);
        months.setVgap(12);
        months.setPrefTileWidth(210);
        months.setPrefTileHeight(135);

        for (int month = 1; month <= 12; month++) {
            months.getChildren().add(monthCard(data, today.withMonth(month).withDayOfMonth(1)));
        }
        panel.getChildren().addAll(title, months);
        return panel;
    }

    private VBox monthCard(ModuleData data, LocalDate monthDate) {
        List<ModuleRow> events = eventsForMonth(data, monthDate);
        VBox card = new VBox(8);
        card.getStyleClass().add("year-month-card");
        card.getChildren().addAll(
                label(monthDate.getMonth().getDisplayName(TextStyle.FULL, activeLocale()), "activity-title"),
                label(pluralEvents(events.size()), "module-big-number-small")
        );
        events.stream().limit(2).forEach(event -> card.getChildren().add(calendarEventChip(event)));
        card.setOnMouseClicked(event -> showMonthDialog(monthDate, events));
        return card;
    }

    private HBox calendarEventChip(ModuleRow event) {
        HBox chip = new HBox(6, iconBubble("fas-calendar-check", "tiny-icon"), label(event.fields().getOrDefault("evento", t("calendar.event.default_title")), "calendar-chip-text"));
        chip.getStyleClass().add("calendar-chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    private VBox monthCalendar(ModuleData data, LocalDate baseDate) {
        Map<Integer, List<ModuleRow>> eventsByDay = calendarEventsByDay(data, baseDate);
        VBox panel = new VBox(12);
        panel.getStyleClass().add("calendar-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);

        Label month = label(
                baseDate.getMonth().getDisplayName(TextStyle.FULL, activeLocale()) + " " + baseDate.getYear(),
                "calendar-month"
        );

        GridPane grid = new GridPane();
        grid.getStyleClass().add("calendar-grid");
        grid.setHgap(8);
        grid.setVgap(8);

        String[] weekdays = localizedWeekdayLetters();
        for (int column = 0; column < weekdays.length; column++) {
            Label dayLabel = label(weekdays[column], "calendar-weekday");
            grid.add(dayLabel, column, 0);
        }

        LocalDate firstDay = baseDate.withDayOfMonth(1);
        int startColumn = firstDay.getDayOfWeek().getValue() - 1;
        int length = firstDay.lengthOfMonth();
        int row = 1;
        int column = startColumn;
        for (int day = 1; day <= length; day++) {
            LocalDate date = baseDate.withDayOfMonth(day);
            grid.add(calendarDay(date, eventsByDay.getOrDefault(day, List.of()), date.equals(LocalDate.now())), column, row);
            column++;
            if (column == 7) {
                column = 0;
                row++;
            }
        }

        panel.getChildren().addAll(month, grid);
        return panel;
    }

    private VBox calendarDay(LocalDate date, List<ModuleRow> events, boolean today) {
        Label number = label(String.valueOf(date.getDayOfMonth()), "calendar-day-number");
        VBox box = new VBox(4, number);
        box.getStyleClass().add("calendar-day");
        if (today) {
            box.getStyleClass().add("calendar-day-today");
        }
        // Mostrar el NOMBRE de los eventos en la propia celda (hasta 3), sin tener
        // que abrir el día. Si hay más, "+N".
        int show = Math.min(events.size(), 3);
        for (int i = 0; i < show; i++) {
            String name = events.get(i).fields().getOrDefault("evento", t("calendar.event.default_title"));
            Label ev = label(name, "calendar-event-badge");
            ev.setMaxWidth(Double.MAX_VALUE);
            ev.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            ev.setWrapText(false);
            box.getChildren().add(ev);
        }
        if (events.size() > show) {
            box.getChildren().add(label("+" + (events.size() - show), "calendar-event-badge"));
        }
        box.setOnMouseClicked(event -> showDayDialog(date, events));
        return box;
    }

    private VBox dayAgenda(ModuleData data, LocalDate today) {
        VBox panel = new VBox(12);
        panel.getStyleClass().add("day-agenda");
        panel.setPrefWidth(330);

        Label title = label(t("calendar.day_agenda.title"), "card-title");
        Label date = label(today.format(DISPLAY_DATE), "section-subtitle");
        panel.getChildren().addAll(new HBox(10, iconBubble("fas-calendar-check", "panel-icon"), new VBox(2, title, date)));

        List<ModuleRow> events = calendarEventsByDay(data, today).getOrDefault(today.getDayOfMonth(), List.of());
        if (events.isEmpty()) {
            panel.getChildren().add(label(t("calendar.day_agenda.no_events"), "status-detail"));
            return panel;
        }

        for (ModuleRow event : events) {
            panel.getChildren().add(calendarEventLine(event));
        }
        return panel;
    }

    private VBox calendarEventLine(ModuleRow event) {
        Label title = label(event.fields().getOrDefault("evento", t("calendar.event.default_title")), "activity-title");
        Label detail = label(event.fields().getOrDefault("detalle", ""), "activity-subtitle");
        Label type = label(event.fields().getOrDefault("tipo", t("calendar.event.default_type")), "activity-value");
        VBox line = new VBox(4, title, detail, type);
        line.getStyleClass().add("calendar-event-line");
        return line;
    }

    private void showDayDialog(LocalDate date, List<ModuleRow> events) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t("calendar.dialog.title"));
        dialog.setHeaderText(null);

        VBox eventList = new VBox(10);
        eventList.getStyleClass().add("calendar-dialog-list");
        if (events.isEmpty()) {
            eventList.getChildren().add(emptyDayPanel(date, dialog));
        } else {
            for (ModuleRow event : events) {
                eventList.getChildren().add(dayEventCard(event, dialog));
            }
        }

        ScrollPane eventScroll = new ScrollPane(eventList);
        eventScroll.getStyleClass().add("calendar-dialog-scroll");
        eventScroll.setFitToWidth(true);
        eventScroll.setPrefViewportHeight(280);

        Button create = new Button(t("calendar.btn.new_event"));
        create.setGraphic(icon("fas-calendar-plus"));
        create.getStyleClass().add("calendar-dialog-primary");
        create.setOnAction(action -> {
            dialog.close();
            showFormDialog("calendar", null, Map.of("date", date.toString()));
        });

        VBox copy = new VBox(4,
                label(date.getDayOfWeek().getDisplayName(TextStyle.FULL, activeLocale()), "eyebrow"),
                label(date.format(DISPLAY_DATE), "calendar-dialog-title"),
                label(events.size() == 1
                        ? t("calendar.dialog.planned_one")
                        : events.size() + t("calendar.dialog.planned_many_suffix"), "section-subtitle")
        );
        HBox header = new HBox(14, iconBubble("fas-calendar-day", "calendar-dialog-icon"), copy);
        header.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actionBar = new HBox(12, header, spacer, create);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        VBox shell = new VBox(18, actionBar, eventScroll);
        shell.getStyleClass().add("calendar-dialog-shell");
        dialog.getDialogPane().setContent(shell);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStyleClass().add("calendar-dialog-pane");
        dialog.showAndWait();
    }

    private VBox emptyDayPanel(LocalDate date, Dialog<?> dialog) {
        Label title = label(t("calendar.dialog.empty.title"), "activity-title");
        Label detail = label(t("calendar.dialog.empty.body"), "activity-subtitle");
        Button create = new Button(t("calendar.dialog.empty.btn"));
        create.setGraphic(icon("fas-plus"));
        create.getStyleClass().add("calendar-dialog-secondary");
        create.setOnAction(action -> {
            dialog.close();
            showFormDialog("calendar", null, Map.of("date", date.toString()));
        });

        VBox panel = new VBox(12, iconBubble("fas-calendar-plus", "calendar-empty-icon"), title, detail, create);
        panel.getStyleClass().add("calendar-empty-panel");
        panel.setAlignment(Pos.CENTER);
        return panel;
    }

    private HBox dayEventCard(ModuleRow event, Dialog<?> dialog) {
        Label title = label(event.fields().getOrDefault("evento", t("calendar.event.default_title")), "calendar-event-card-title");
        Label detail = label(event.fields().getOrDefault("detalle", t("calendar.event.no_detail")), "calendar-event-card-detail");
        // CAL-FIX (Benjamin 2026-06-09): humanizar event_type. Los volcados
        // del calendario laboral usan HOLIDAY/WORK_ADJUSTMENT/WORK_CLOSURE
        // que son códigos internos. Si hay key i18n, la usamos; si no,
        // mantenemos el valor original (back-compat con eventos antiguos).
        String rawType = event.fields().getOrDefault("tipo", t("calendar.event.default_type"));
        Label type = label(humanizeCalendarEventType(rawType), "calendar-event-card-type");
        // PORT-5 CAL-A — anadir variante de color segun event_type.
        String variantClass = calendarEventTypeVariantClass(rawType);
        if (variantClass != null) type.getStyleClass().add(variantClass);
        VBox copy = new VBox(5, title, detail, type);
        HBox.setHgrow(copy, Priority.ALWAYS);

        Button edit = new Button(t("common.btn.edit"));
        edit.setGraphic(icon("fas-pen"));
        edit.getStyleClass().add("calendar-dialog-secondary");
        edit.setOnAction(action -> {
            dialog.close();
            showFormDialog("calendar", event);
        });

        Button delete = new Button(t("common.btn.delete"));
        delete.setGraphic(icon("fas-trash-alt"));
        delete.getStyleClass().add("calendar-dialog-danger");
        delete.setOnAction(action -> {
            dialog.close();
            deleteCalendarEvent(event.id());
        });

        HBox actions = new HBox(8, edit, delete);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox card = new HBox(14, iconBubble("fas-calendar-check", "calendar-event-card-icon"), copy, actions);
        card.getStyleClass().add("calendar-event-card");
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    /**
     * PORT-5 CAL-A — Variante CSS de color para el badge {@code
     * calendar-event-card-type} segun el tipo de evento. Devuelve null
     * cuando no hay variante conocida (badge queda con el color neutro
     * por defecto).
     */
    private String calendarEventTypeVariantClass(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String up = raw.trim().toUpperCase(java.util.Locale.ROOT);
        switch (up) {
            case "HOLIDAY":         return "calendar-event-card-type--holiday";
            case "WORK_ADJUSTMENT": return "calendar-event-card-type--work-adjustment";
            case "WORK_CLOSURE":    return "calendar-event-card-type--work-closure";
            case "GENERAL":         return "calendar-event-card-type--general";
            default: /* sigue al matching tolerante */
        }
        // CAL-A v2 — mismo matching keyword que humanize.
        String norm = stripDiacritics(up);
        if (norm.contains("FESTIV") || norm.contains("FESTIVIDAD")) {
            return "calendar-event-card-type--holiday";
        }
        if (norm.contains("AJUST")) {
            return "calendar-event-card-type--work-adjustment";
        }
        if (norm.contains("CIERRE") || norm.contains("CERRAD")) {
            return "calendar-event-card-type--work-closure";
        }
        return null;
    }

    private void showMonthDialog(LocalDate monthDate, List<ModuleRow> events) {
        StringBuilder message = new StringBuilder();
        if (events.isEmpty()) {
            message.append(t("calendar.dialog.month.no_events"));
        } else {
            for (ModuleRow event : events) {
                message.append("- ")
                        .append(event.fields().getOrDefault("fecha", ""))
                        .append(" · ")
                        .append(event.fields().getOrDefault("evento", t("calendar.event.default_title")))
                        .append("\n");
            }
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message.toString(), ButtonType.OK);
        alert.setTitle(t("calendar.dialog.title"));
        alert.setHeaderText(monthDate.getMonth().getDisplayName(TextStyle.FULL, activeLocale()) + " " + monthDate.getYear());
        alert.showAndWait();
    }

    private List<ModuleRow> eventsForDate(ModuleData data, LocalDate date) {
        return data.records().stream()
                .filter(row -> row.fields().getOrDefault("fecha", "").equals(date.toString()))
                .sorted(Comparator.comparing(row -> row.fields().getOrDefault("evento", "")))
                .toList();
    }

    private List<ModuleRow> eventsForMonth(ModuleData data, LocalDate monthDate) {
        return data.records().stream()
                .filter(row -> {
                    String value = row.fields().getOrDefault("fecha", "");
                    if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        return false;
                    }
                    LocalDate eventDate = LocalDate.parse(value);
                    return eventDate.getYear() == monthDate.getYear() && eventDate.getMonth() == monthDate.getMonth();
                })
                .sorted(Comparator.comparing(row -> row.fields().getOrDefault("fecha", "")))
                .toList();
    }

    private Map<Integer, List<ModuleRow>> calendarEventsByDay(ModuleData data, LocalDate baseDate) {
        Map<Integer, List<ModuleRow>> events = new TreeMap<>();
        for (ModuleRow row : data.records()) {
            String value = row.fields().getOrDefault("fecha", "");
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                continue;
            }
            LocalDate eventDate = LocalDate.parse(value);
            if (eventDate.getMonth() == baseDate.getMonth() && eventDate.getYear() == baseDate.getYear()) {
                events.computeIfAbsent(eventDate.getDayOfMonth(), ignored -> new ArrayList<>()).add(row);
            }
        }
        events.values().forEach(list -> list.sort(Comparator.comparing(row -> row.fields().getOrDefault("evento", ""))));
        return events;
    }

    private void deleteCalendarEvent(String id) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                apiClient.delete("calendar", id);
                return null;
            }
        };
        task.setOnSucceeded(event -> showModule("calendar"));
        task.setOnFailed(event -> showError(t("deleteFailed"), t("backendCheck")));
        start(task, "calendar-delete-" + id);
    }
}