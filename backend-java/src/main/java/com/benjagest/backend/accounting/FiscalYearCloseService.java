package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cierre de ejercicio fiscal. Estado simple: OPEN → PRE_CLOSE → CLOSED.
 *
 * - precalculate(year): suma ingresos (sales_invoices VALIDATED del año
 *   ventana) y gastos estimados (suma payslips + amortizaciones). Es una
 *   estimacion; el cierre real lo finaliza el operador revisando.
 * - close(year, manualValues): persiste resultado, IS estimado, aplicacion
 *   de resultado a reservas/dividendos. Marca como CLOSED.
 * - reopen(year): permite reabrir si OWNER lo decide (audit).
 *
 * No realiza asientos contables automaticos (necesita libro diario).
 */
@Service
public class FiscalYearCloseService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final ClosingEntriesService closingEntries;
    private final com.benjagest.backend.audit.AuditService auditService;

    public FiscalYearCloseService(JdbcTemplate jdbcTemplate,
                                   TenantContext tenantContext,
                                   CurrentUserService currentUserService,
                                   ClosingEntriesService closingEntries,
                                   com.benjagest.backend.audit.AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.closingEntries = closingEntries;
        this.auditService = auditService;
    }

    public List<CloseView> list() {
        return jdbcTemplate.query("""
                SELECT id, period_year, status,
                       income_total, expense_total, result_amount,
                       tax_amount, result_after_tax,
                       reserves_allocation, dividends_allocation,
                       accumulated_losses_allocation,
                       closed_at, closed_by, reopened_at, reopened_by,
                       notes, created_at, updated_at
                  FROM fiscal_year_closes
                 WHERE company_id = ?
                 ORDER BY period_year DESC
                """, this::mapView, tenantContext.getCurrentCompanyId());
    }

    public CloseView get(int year) {
        return jdbcTemplate.query("""
                SELECT id, period_year, status,
                       income_total, expense_total, result_amount,
                       tax_amount, result_after_tax,
                       reserves_allocation, dividends_allocation,
                       accumulated_losses_allocation,
                       closed_at, closed_by, reopened_at, reopened_by,
                       notes, created_at, updated_at
                  FROM fiscal_year_closes
                 WHERE company_id = ? AND period_year = ?
                """, this::mapView, tenantContext.getCurrentCompanyId(), year)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay cierre para " + year));
    }

    @Transactional
    public CloseView precalculate(int year) {
        BigDecimal income = sumIncome(year);
        BigDecimal expenses = sumExpenses(year);
        BigDecimal result = income.subtract(expenses);
        // Estimacion IS al 25% (regla general PYME)
        BigDecimal estimatedTax = result.signum() > 0
                ? result.multiply(new BigDecimal("0.25"))
                        .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal afterTax = result.subtract(estimatedTax);

        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fiscal_year_closes
                 WHERE company_id = ? AND period_year = ?
                """, Integer.class, tenantContext.getCurrentCompanyId(), year);

        if (existing != null && existing > 0) {
            jdbcTemplate.update("""
                    UPDATE fiscal_year_closes
                       SET income_total = ?, expense_total = ?, result_amount = ?,
                           tax_amount = ?, result_after_tax = ?,
                           status = CASE WHEN status = 'CLOSED' THEN 'CLOSED'
                                          ELSE 'PRE_CLOSE' END
                     WHERE company_id = ? AND period_year = ?
                    """,
                    income, expenses, result, estimatedTax, afterTax,
                    tenantContext.getCurrentCompanyId(), year);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO fiscal_year_closes (
                        id, company_id, period_year, status,
                        income_total, expense_total, result_amount,
                        tax_amount, result_after_tax
                    ) VALUES (?, ?, ?, 'PRE_CLOSE', ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    tenantContext.getCurrentCompanyId(), year,
                    income, expenses, result, estimatedTax, afterTax);
        }
        return get(year);
    }

    @Transactional
    public CloseView close(int year, CloseRequest req) {
        CloseView current = get(year);
        if ("CLOSED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El ejercicio ya está cerrado");
        }
        BigDecimal reserves = req.reservesAllocation() == null ? BigDecimal.ZERO : req.reservesAllocation();
        BigDecimal divs = req.dividendsAllocation() == null ? BigDecimal.ZERO : req.dividendsAllocation();
        BigDecimal losses = req.accumulatedLossesAllocation() == null ? BigDecimal.ZERO : req.accumulatedLossesAllocation();
        BigDecimal after = current.resultAfterTax() == null ? BigDecimal.ZERO : current.resultAfterTax();
        BigDecimal totalAlloc = reserves.add(divs).add(losses);
        if (after.subtract(totalAlloc).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La suma de aplicaciones (" + totalAlloc + ") debe coincidir con el resultado tras IS ("
                            + after + ")");
        }

        String userId;
        try { userId = currentUserService.require().userId(); }
        catch (Exception ex) { userId = null; }

        // Asientos contables del cierre — best effort. Si falla por falta
        // de movimientos o de cuentas PGC, el cierre legal sigue adelante
        // sin asiento (es lo razonable para empresas que llevan caja).
        String regEntryId = null;
        String closeEntryId = null;
        try {
            regEntryId = closingEntries.generateRegularizationEntry(year, userId);
        } catch (Exception ex) {
            System.err.println("[year-close] regularización no generada para "
                    + year + ": " + ex.getMessage());
        }
        if (regEntryId != null) {
            try {
                closeEntryId = closingEntries.generateClosingEntry(year, userId);
            } catch (Exception ex) {
                System.err.println("[year-close] asiento cierre no generado para "
                        + year + ": " + ex.getMessage());
            }
        }

        jdbcTemplate.update("""
                UPDATE fiscal_year_closes
                   SET status = 'CLOSED',
                       reserves_allocation = ?, dividends_allocation = ?,
                       accumulated_losses_allocation = ?,
                       notes = ?,
                       regularization_entry_id = ?, closing_entry_id = ?,
                       closed_at = CURRENT_TIMESTAMP, closed_by = ?
                 WHERE company_id = ? AND period_year = ?
                """,
                reserves, divs, losses, blank(req.notes()),
                regEntryId, closeEntryId, userId,
                tenantContext.getCurrentCompanyId(), year);

        // Sincronizar fiscal_years.status = CLOSED si existe la fila.
        jdbcTemplate.update("""
                UPDATE fiscal_years
                   SET status = 'CLOSED'
                 WHERE company_id = ? AND year_number = ?
                """, tenantContext.getCurrentCompanyId(), year);

        // LOCK (2026-07-07): quién y cuándo cerró, en la auditoría.
        auditService.recordFiscalYearClosed(userId,
                tenantContext.getCurrentCompanyId(), year);

        return get(year);
    }

    @Transactional
    public CloseView reopen(int year, String reason) {
        CloseView current = get(year);
        if (!"CLOSED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El ejercicio no está cerrado");
        }
        String userId;
        try { userId = currentUserService.require().userId(); }
        catch (Exception ex) { userId = null; }
        String newNotes = (current.notes() == null ? "" : current.notes() + "\n")
                + "[REABIERTO " + Instant.now() + "] " + (reason == null ? "" : reason);
        jdbcTemplate.update("""
                UPDATE fiscal_year_closes
                   SET status = 'REOPENED',
                       reopened_at = CURRENT_TIMESTAMP, reopened_by = ?,
                       notes = ?
                 WHERE company_id = ? AND period_year = ?
                """, userId, newNotes, tenantContext.getCurrentCompanyId(), year);

        // LOCK (2026-07-07): reabrir de verdad. close() marca
        // fiscal_years.status='CLOSED' (que es lo que mira
        // FiscalYearGuardService), pero reopen no lo devolvía a OPEN →
        // tras reabrir, TODO seguía bloqueado con 409. Estado
        // inconsistente detectado en la auditoría integral 2026-07-07.
        jdbcTemplate.update("""
                UPDATE fiscal_years
                   SET status = 'OPEN'
                 WHERE company_id = ? AND year_number = ?
                """, tenantContext.getCurrentCompanyId(), year);

        auditService.recordFiscalYearReopened(userId,
                tenantContext.getCurrentCompanyId(), year, reason);

        return get(year);
    }

    private BigDecimal sumIncome(int year) {
        BigDecimal v = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(total), 0)
                  FROM sales_invoices
                 WHERE company_id = ?
                   AND status IN ('VALIDATED', 'PAID', 'PARTIAL', 'OVERDUE')
                   AND YEAR(invoice_date) = ?
                """, BigDecimal.class, tenantContext.getCurrentCompanyId(), year);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal sumExpenses(int year) {
        BigDecimal payroll = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(gross_amount + ss_employee_amount * 0 + 0), 0)
                  FROM payslips
                 WHERE company_id = ? AND period_year = ?
                """, BigDecimal.class, tenantContext.getCurrentCompanyId(), year);
        BigDecimal depreciation = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(depreciation_amount), 0)
                  FROM fixed_asset_depreciations
                 WHERE company_id = ? AND period_year = ?
                """, BigDecimal.class, tenantContext.getCurrentCompanyId(), year);
        return (payroll == null ? BigDecimal.ZERO : payroll)
                .add(depreciation == null ? BigDecimal.ZERO : depreciation);
    }

    private CloseView mapView(ResultSet rs, int rowNum) throws SQLException {
        Timestamp cl = rs.getTimestamp("closed_at");
        Timestamp rp = rs.getTimestamp("reopened_at");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new CloseView(
                rs.getString("id"), rs.getInt("period_year"), rs.getString("status"),
                rs.getBigDecimal("income_total"),
                rs.getBigDecimal("expense_total"),
                rs.getBigDecimal("result_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("result_after_tax"),
                rs.getBigDecimal("reserves_allocation"),
                rs.getBigDecimal("dividends_allocation"),
                rs.getBigDecimal("accumulated_losses_allocation"),
                cl == null ? null : cl.toInstant(),
                rs.getString("closed_by"),
                rp == null ? null : rp.toInstant(),
                rs.getString("reopened_by"),
                rs.getString("notes"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }

    private String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public record CloseView(
            String id, int periodYear, String status,
            BigDecimal incomeTotal, BigDecimal expenseTotal, BigDecimal resultAmount,
            BigDecimal taxAmount, BigDecimal resultAfterTax,
            BigDecimal reservesAllocation, BigDecimal dividendsAllocation,
            BigDecimal accumulatedLossesAllocation,
            Instant closedAt, String closedBy,
            Instant reopenedAt, String reopenedBy,
            String notes, Instant createdAt, Instant updatedAt
    ) {}

    public record CloseRequest(
            BigDecimal reservesAllocation,
            BigDecimal dividendsAllocation,
            BigDecimal accumulatedLossesAllocation,
            String notes
    ) {}

    public record ReopenRequest(String reason) {}

    @RestController
    @RequestMapping("/api/accounting/year-close")
    @RequiresModule("accounting")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class FiscalYearCloseController {
        private final FiscalYearCloseService service;
        private final ClosingEntriesService closingEntries;
        public FiscalYearCloseController(FiscalYearCloseService service,
                                          ClosingEntriesService closingEntries) {
            this.service = service;
            this.closingEntries = closingEntries;
        }

        @GetMapping
        public List<CloseView> list() { return service.list(); }

        @GetMapping("/{year}")
        public CloseView get(@PathVariable("year") int year) { return service.get(year); }

        @PostMapping("/{year}/precalculate")
        public CloseView precalculate(@PathVariable("year") int year) {
            return service.precalculate(year);
        }

        // LOCK (2026-07-07): cerrar/reabrir un ejercicio cambia qué puede
        // tocarse en la contabilidad de todo un año — no es operativa
        // diaria. Cerrar queda para quien lleva los libros; reabrir un
        // cierre definitivo, SOLO el OWNER (mismo criterio que el javadoc
        // de FiscalYearGuardService). Antes cualquier EMPLOYEE podía
        // reabrir, tocar y volver a cerrar sin dejar rastro.
        @PutMapping("/{year}/close")
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public CloseView close(@PathVariable("year") int year, @RequestBody CloseRequest req) {
            return service.close(year, req);
        }

        @PutMapping("/{year}/reopen")
        @RequiresRole({"OWNER"})
        public CloseView reopen(@PathVariable("year") int year, @RequestBody ReopenRequest req) {
            return service.reopen(year, req == null ? null : req.reason());
        }

        /**
         * Vista previa de la regularización contable (6x/7x → 129).
         * Devuelve totales de gastos, ingresos y diferencia (resultado)
         * sin crear ningún asiento. Útil para mostrar en UI antes de
         * confirmar el cierre.
         */
        @GetMapping("/{year}/preview-regularization")
        public ClosingEntriesService.RegularizationPreview previewRegularization(
                @PathVariable("year") int year) {
            return closingEntries.previewRegularization(year);
        }
    }
}
