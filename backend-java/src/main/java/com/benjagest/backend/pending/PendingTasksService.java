package com.benjagest.backend.pending;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AVISOS — agregador EN VIVO de "tareas pendientes" (estado actual, no eventos).
 * Cuenta estados accionables por empresa, para el centro de avisos y el badge.
 *
 * <p>Dos ámbitos:
 * <ul>
 *   <li>{@link #forCurrent()} — la empresa actual (tenant): vale para el
 *       empresario y para "Mi gestión" / un cliente abierto.</li>
 *   <li>{@link #forPortfolio()} — la asesoría sobre TODA su cartera
 *       (companies.parent_company_id = la asesoría) + ella misma.</li>
 * </ul>
 *
 * Las etiquetas y el destino de navegación de cada bucket los resuelve la UI
 * por i18n a partir del {@code type}.
 */
@Service
public class PendingTasksService {

    private static final String URGENT = "URGENT";
    private static final String WARNING = "WARNING";
    private static final String INFO = "INFO";

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public PendingTasksService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public record Bucket(String type, int count, String severity) {}

    public List<Bucket> forCurrent() {
        return forCompanies(List.of(tenant.getCurrentCompanyId()));
    }

    public List<Bucket> forPortfolio() {
        String self = tenant.getCurrentCompanyId();
        List<String> ids = new ArrayList<>();
        ids.add(self);
        ids.addAll(jdbc.queryForList(
                "SELECT id FROM companies WHERE parent_company_id = ?", String.class, self));
        return forCompanies(ids);
    }

    private List<Bucket> forCompanies(List<String> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) return List.of();
        String in = companyIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Object[] args = companyIds.toArray();
        List<Bucket> out = new ArrayList<>();
        add(out, "DRAFT_JOURNAL", WARNING, count("journal_entries",
                "status = 'DRAFT' AND auto_proposed = TRUE", in, args));
        add(out, "OVERDUE_INVOICES", URGENT, count("sales_invoices",
                "status = 'VALIDATED' AND payment_status IN ('PENDING','PARTIAL') AND due_date < CURRENT_DATE", in, args));
        add(out, "DRAFT_PAYSLIPS", WARNING, count("payslips",
                "status IN ('DRAFT','CALCULATED')", in, args));
        add(out, "LEAVE_REQUESTS", WARNING, count("employee_leave_requests",
                "status = 'REQUESTED'", in, args));
        add(out, "UNDELIVERED_PAYSLIPS", INFO, count("payslips",
                "status = 'PAID' AND delivered_at IS NULL", in, args));
        add(out, "OVERDUE_FILINGS", URGENT, count("tax_filings",
                "status IN ('DRAFT','READY') AND deadline_at < CURRENT_DATE", in, args));
        add(out, "DEHU_PENDING", WARNING, count("dehu_notifications",
                "status = 'PENDING'", in, args));
        add(out, "UNRECONCILED_BANK", INFO, count("bank_movements",
                "status = 'UNRECONCILED'", in, args));
        add(out, "VERIFACTU_ERROR", WARNING, count("verifactu_registry",
                "status = 'ERROR'", in, args));
        return out;
    }

    private void add(List<Bucket> out, String type, String severity, int count) {
        if (count > 0) out.add(new Bucket(type, count, severity));
    }

    /** Cuenta filas. table/cond son constantes del código (sin input externo);
     *  los company_id van parametrizados. */
    private int count(String table, String cond, String inClause, Object[] args) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE company_id IN (" + inClause + ") AND " + cond,
                    Integer.class, args);
            return n == null ? 0 : n;
        } catch (Exception ex) {
            return 0; // tabla/columna no disponible en ese tenant → no rompe el panel
        }
    }

    @RestController
    @RequestMapping("/api/pending-tasks")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class PendingTasksController {
        private final PendingTasksService service;
        public PendingTasksController(PendingTasksService service) { this.service = service; }

        @GetMapping
        public List<Bucket> current() { return service.forCurrent(); }

        @GetMapping("/portfolio")
        public List<Bucket> portfolio() { return service.forPortfolio(); }
    }
}
