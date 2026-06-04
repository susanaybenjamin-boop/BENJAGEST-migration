package com.benjagest.backend.timeclock;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catalogo de tipos de evento de fichaje, configurable por empresa.
 *
 * Lo usan tres consumidores:
 *
 *   1) UI sub-tab Fichajes: pinta los botones dinamicamente segun los
 *      tipos activos, en orden display_order.
 *   2) TimeClockService.punch: valida que el eventType recibido existe
 *      y esta activo en la empresa actual.
 *   3) TimeClockService.computeWorkedTime (futuro): usa is_work_time y
 *      is_pause para calcular horas reales trabajadas.
 *
 * Defensas:
 *   - No se puede borrar un tipo si hay eventos historicos con ese code
 *     (referidos por time_clock_events.event_type), se desactiva.
 *   - El code es UPPERCASE y unico por empresa.
 *   - Si la empresa no tiene seed, ensureSeedFor lo crea al primer acceso.
 */
@Service
public class TimeClockEventTypeService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public TimeClockEventTypeService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<EventTypeView> list(boolean includeInactive) {
        ensureSeedFor(tenantContext.getCurrentCompanyId());
        StringBuilder sql = new StringBuilder("""
                SELECT id, code, label_es, label_en, icon, display_order,
                       is_work_time, is_pause, active
                  FROM time_clock_event_types
                 WHERE company_id = ?
                """);
        if (!includeInactive) sql.append(" AND active = TRUE");
        sql.append(" ORDER BY display_order, code");
        return jdbcTemplate.query(sql.toString(), this::mapView, tenantContext.getCurrentCompanyId());
    }

    /**
     * Verifica si un code existe y esta activo para la empresa actual.
     * Devuelve true si el code es valido, false si no.
     */
    public boolean isValidCode(String code) {
        if (code == null || code.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM time_clock_event_types
                 WHERE company_id = ? AND code = ? AND active = TRUE
                """, Integer.class,
                tenantContext.getCurrentCompanyId(), code.toUpperCase());
        return count != null && count > 0;
    }

    @Transactional
    public EventTypeView create(UpsertRequest req) {
        validate(req);
        String code = req.code().toUpperCase().trim();
        // Si existe inactivo, reactivar y actualizar campos
        var existing = jdbcTemplate.query("""
                SELECT id FROM time_clock_event_types
                 WHERE company_id = ? AND code = ?
                """,
                (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), code)
                .stream().findFirst();
        if (existing.isPresent()) {
            return update(existing.get(), req);
        }
        String id = UUID.randomUUID().toString();
        int order = req.displayOrder() == null
                ? nextDisplayOrder()
                : req.displayOrder();
        jdbcTemplate.update("""
                INSERT INTO time_clock_event_types (
                    id, company_id, code, label_es, label_en, icon,
                    display_order, is_work_time, is_pause, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                code, req.labelEs(), req.labelEn(),
                blank(req.icon()), order,
                req.isWorkTime() == null || req.isWorkTime(),
                req.isPause() != null && req.isPause(),
                req.active() == null || req.active());
        return findById(id);
    }

    @Transactional
    public EventTypeView update(String id, UpsertRequest req) {
        validate(req);
        int n = jdbcTemplate.update("""
                UPDATE time_clock_event_types
                   SET label_es = ?, label_en = ?, icon = ?,
                       display_order = COALESCE(?, display_order),
                       is_work_time = ?, is_pause = ?, active = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.labelEs(), req.labelEn(), blank(req.icon()),
                req.displayOrder(),
                req.isWorkTime() == null || req.isWorkTime(),
                req.isPause() != null && req.isPause(),
                req.active() == null || req.active(),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo no encontrado");
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        // Si hay eventos historicos con este code, no borramos: desactivamos.
        String code = jdbcTemplate.query("""
                SELECT code FROM time_clock_event_types WHERE id = ? AND company_id = ?
                """, (rs, n) -> rs.getString("code"),
                id, tenantContext.getCurrentCompanyId())
                .stream().findFirst().orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo no encontrado"));

        Integer usage = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM time_clock_events
                 WHERE company_id = ? AND event_type = ?
                """, Integer.class, tenantContext.getCurrentCompanyId(), code);
        if (usage != null && usage > 0) {
            jdbcTemplate.update("""
                    UPDATE time_clock_event_types SET active = FALSE
                     WHERE id = ? AND company_id = ?
                    """, id, tenantContext.getCurrentCompanyId());
            return;
        }
        jdbcTemplate.update("""
                DELETE FROM time_clock_event_types WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
    }

    /**
     * Si la empresa no tiene tipos seed, los crea (los 4 originales).
     * Idempotente: si ya tiene >= 1, no toca nada.
     */
    @Transactional
    public void ensureSeedFor(String companyId) {
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM time_clock_event_types WHERE company_id = ?
                """, Integer.class, companyId);
        if (existing != null && existing > 0) return;

        Object[][] seed = {
                {"IN", "Entrada", "Clock in", "fas-sign-in-alt", 1, true, false},
                {"OUT", "Salida", "Clock out", "fas-sign-out-alt", 2, false, false},
                {"BREAK_START", "Inicio pausa", "Break start", "fas-coffee", 3, false, true},
                {"BREAK_END", "Fin pausa", "Break end", "fas-utensils", 4, true, true}
        };
        for (Object[] r : seed) {
            jdbcTemplate.update("""
                    INSERT INTO time_clock_event_types (
                        id, company_id, code, label_es, label_en, icon,
                        display_order, is_work_time, is_pause, active
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                    """,
                    UUID.randomUUID().toString(), companyId,
                    r[0], r[1], r[2], r[3], r[4], r[5], r[6]);
        }
    }

    private EventTypeView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, code, label_es, label_en, icon, display_order,
                       is_work_time, is_pause, active
                  FROM time_clock_event_types
                 WHERE id = ? AND company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo no encontrado"));
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code requerido");
        }
        if (!req.code().matches("^[A-Z][A-Z0-9_]{1,39}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "code debe empezar por letra y solo contener mayusculas, digitos o _ (1-40 chars)");
        }
        if (!StringUtils.hasText(req.labelEs())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "labelEs requerido");
        }
        if (!StringUtils.hasText(req.labelEn())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "labelEn requerido");
        }
    }

    private int nextDisplayOrder() {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(display_order), 0) FROM time_clock_event_types
                 WHERE company_id = ?
                """, Integer.class, tenantContext.getCurrentCompanyId());
        return (max == null ? 0 : max) + 1;
    }

    private String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private EventTypeView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new EventTypeView(
                rs.getString("id"),
                rs.getString("code"),
                rs.getString("label_es"),
                rs.getString("label_en"),
                rs.getString("icon"),
                rs.getInt("display_order"),
                rs.getBoolean("is_work_time"),
                rs.getBoolean("is_pause"),
                rs.getBoolean("active")
        );
    }

    public record EventTypeView(
            String id, String code, String labelEs, String labelEn,
            String icon, int displayOrder,
            boolean isWorkTime, boolean isPause, boolean active
    ) {}

    public record UpsertRequest(
            String code, String labelEs, String labelEn, String icon,
            Integer displayOrder, Boolean isWorkTime, Boolean isPause, Boolean active
    ) {}

    @RestController
    @RequestMapping("/api/timeclock/event-types")
    @RequiresModule("time-clock")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class EventTypeController {
        private final TimeClockEventTypeService service;
        public EventTypeController(TimeClockEventTypeService service) { this.service = service; }

        @GetMapping
        public List<EventTypeView> list(@RequestParam(value = "includeInactive", defaultValue = "false") boolean inc) {
            return service.list(inc);
        }

        @PostMapping
        public EventTypeView create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public EventTypeView update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
