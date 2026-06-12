package com.benjagest.backend.fiscal;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CAL-FISCAL — Calendario de vencimientos AEAT.
 *
 * <p>Las filas con {@code company_id IS NULL} son vencimientos
 * genéricos (válidos para todas las empresas). Las filas con
 * {@code company_id} concreto son personalizaciones de una empresa
 * (p.ej. cambia su periodicidad o lo da por presentado).
 */
@Service
public class TaxCalendarService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUser;

    public TaxCalendarService(JdbcTemplate jdbc, TenantContext tenant,
                                CurrentUserService currentUser) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUser = currentUser;
    }

    /** Vencimientos próximos (genéricos + de la empresa) en una ventana. */
    public List<TaxCalendarEvent> findUpcoming(int days) {
        String companyId = tenant.getCurrentCompanyId();
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(Math.max(1, days));
        return jdbc.query("""
                SELECT id, company_id, model_code, period_label, due_date,
                       description, fiscal_year, status, submitted_at,
                       submitted_by, notes, created_at
                  FROM tax_calendar_events
                 WHERE due_date BETWEEN ? AND ?
                   AND (company_id IS NULL OR company_id = ?)
                   AND status = 'PENDING'
                 ORDER BY due_date ASC, model_code
                """, MAPPER,
                java.sql.Date.valueOf(from),
                java.sql.Date.valueOf(to),
                companyId);
    }

    /** Todos los vencimientos del año (incluido SUBMITTED). */
    public List<TaxCalendarEvent> findByYear(int year) {
        String companyId = tenant.getCurrentCompanyId();
        return jdbc.query("""
                SELECT id, company_id, model_code, period_label, due_date,
                       description, fiscal_year, status, submitted_at,
                       submitted_by, notes, created_at
                  FROM tax_calendar_events
                 WHERE fiscal_year = ?
                   AND (company_id IS NULL OR company_id = ?)
                 ORDER BY due_date ASC
                """, MAPPER, year, companyId);
    }

    /**
     * Marca un evento genérico como SUBMITTED creando una COPIA
     * personalizada para la empresa (no mutamos el genérico — afectaría
     * a todas las demás empresas). Si la fila ya es específica de la
     * empresa, la actualiza directamente.
     */
    @Transactional
    public TaxCalendarEvent markSubmitted(String id, String notes) {
        TaxCalendarEvent event = getById(id);
        String companyId = tenant.getCurrentCompanyId();
        String userId;
        try { userId = currentUser.require().userId(); }
        catch (Exception ex) { userId = null; }
        if (event.companyId() == null) {
            // Es genérico → clonamos como específico de la empresa.
            String newId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO tax_calendar_events
                           (id, company_id, model_code, period_label, due_date,
                            description, fiscal_year, status, submitted_at,
                            submitted_by, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', CURRENT_TIMESTAMP, ?, ?)
                    """,
                    newId, companyId, event.modelCode(), event.periodLabel(),
                    java.sql.Date.valueOf(event.dueDate()),
                    event.description(), event.fiscalYear(),
                    userId, notes);
            return getById(newId);
        }
        if (!companyId.equals(event.companyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No puedes marcar como presentado un evento de otra empresa");
        }
        jdbc.update("""
                UPDATE tax_calendar_events
                   SET status = 'SUBMITTED',
                       submitted_at = CURRENT_TIMESTAMP,
                       submitted_by = ?,
                       notes = ?
                 WHERE id = ?
                """, userId, notes, id);
        return getById(id);
    }

    private TaxCalendarEvent getById(String id) {
        return jdbc.query("""
                SELECT id, company_id, model_code, period_label, due_date,
                       description, fiscal_year, status, submitted_at,
                       submitted_by, notes, created_at
                  FROM tax_calendar_events
                 WHERE id = ?
                """, MAPPER, id).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado"));
    }

    private static final RowMapper<TaxCalendarEvent> MAPPER = (rs, i) -> {
        Timestamp sub = rs.getTimestamp("submitted_at");
        Timestamp created = rs.getTimestamp("created_at");
        java.sql.Date due = rs.getDate("due_date");
        return new TaxCalendarEvent(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("model_code"),
                rs.getString("period_label"),
                due == null ? null : due.toLocalDate(),
                rs.getString("description"),
                rs.getInt("fiscal_year"),
                rs.getString("status"),
                sub == null ? null : sub.toInstant(),
                rs.getString("submitted_by"),
                rs.getString("notes"),
                created == null ? null : created.toInstant()
        );
    };

    public record TaxCalendarEvent(
            String id, String companyId, String modelCode, String periodLabel,
            LocalDate dueDate, String description, int fiscalYear,
            String status, Instant submittedAt, String submittedBy,
            String notes, Instant createdAt
    ) {}

    @RestController
    @RequestMapping("/api/fiscal/tax-calendar")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final TaxCalendarService service;

        public Controller(TaxCalendarService service) { this.service = service; }

        @GetMapping("/upcoming")
        public List<TaxCalendarEvent> upcoming(
                @RequestParam(value = "days", required = false, defaultValue = "60") int days) {
            return service.findUpcoming(days);
        }

        @GetMapping("/year/{year}")
        public List<TaxCalendarEvent> byYear(@PathVariable("year") int year) {
            return service.findByYear(year);
        }

        @PostMapping("/{id}/mark-submitted")
        public TaxCalendarEvent markSubmitted(
                @PathVariable("id") String id,
                @RequestParam(value = "notes", required = false) String notes) {
            return service.markSubmitted(id, notes);
        }
    }
}
