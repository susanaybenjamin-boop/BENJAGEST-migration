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

    /** Bucket con la empresa a la que pertenece (vista cartera de la asesoría). */
    public record CompanyBucket(String companyId, String companyName,
                                String type, int count, String severity) {}

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

    /**
     * Cartera DESGLOSADA por empresa: para que la asesoría vea de QUÉ cliente es
     * cada aviso y pueda ir directa (antes el agregado no decía de quién era —
     * había que ir cliente por cliente). La asesoría (self) va primera.
     */
    public List<CompanyBucket> forPortfolioDetailed() {
        String self = tenant.getCurrentCompanyId();
        List<String[]> companies = jdbc.query("""
                SELECT id, legal_name FROM companies
                 WHERE id = ? OR parent_company_id = ?
                 ORDER BY (id = ?) DESC, legal_name
                """, (rs, n) -> new String[]{rs.getString("id"), rs.getString("legal_name")},
                self, self, self);
        List<CompanyBucket> out = new ArrayList<>();
        for (String[] c : companies) {
            for (Bucket b : forCompanies(List.of(c[0]))) {
                out.add(new CompanyBucket(c[0], c[1], b.type(), b.count(), b.severity()));
            }
        }
        return out;
    }

    private List<Bucket> forCompanies(List<String> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) return List.of();
        String in = companyIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Object[] args = companyIds.toArray();
        List<Bucket> out = new ArrayList<>();
        add(out, "DRAFT_JOURNAL", WARNING, count("journal_entries",
                "status = 'DRAFT' AND auto_proposed = TRUE", in, args));
        add(out, "DRAFT_PURCHASES", WARNING, count("purchase_invoices",
                "status = 'DRAFT'", in, args));
        add(out, "DRAFT_SALES", WARNING, count("sales_invoices",
                "status = 'DRAFT'", in, args));
        add(out, "OVERDUE_INVOICES", URGENT, count("sales_invoices",
                "status = 'VALIDATED' AND payment_status IN ('PENDING','PARTIAL') AND due_date < CURRENT_DATE"
                // Excluir rectificativas que anulan una original ya VOIDED (netean a
                // cero con ella). Mismo criterio que ClientFinancialsService.
                + " AND NOT (sales_invoices.original_invoice_id IS NOT NULL AND EXISTS ("
                + " SELECT 1 FROM sales_invoices o WHERE o.id = sales_invoices.original_invoice_id AND o.status = 'VOIDED'))",
                in, args));
        add(out, "DRAFT_PAYSLIPS", WARNING, count("payslips",
                "status IN ('DRAFT','CALCULATED')", in, args));
        add(out, "LEAVE_REQUESTS", WARNING, count("employee_leave_requests",
                "status = 'REQUESTED'", in, args));
        add(out, "SUPPLIERS_NO_NIF", WARNING, countSuppliersNoNif(in, args));
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

    /**
     * Proveedores del año en curso cuyo total supera el umbral del 347 (3.005,06 €)
     * pero NO tienen NIF: la asesoría debe pedírselo antes de presentar el 347
     * (sin NIF no entran en el modelo). Agrupa por nombre de proveedor.
     */
    private int countSuppliersNoNif(String inClause, Object[] args) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ("
                  + "  SELECT supplier_name FROM purchase_invoices"
                  + "   WHERE company_id IN (" + inClause + ")"
                  + "     AND (supplier_nif IS NULL OR supplier_nif = '')"
                  + "     AND YEAR(invoice_date) = YEAR(CURRENT_DATE)"
                  + "   GROUP BY supplier_name"
                  + "   HAVING SUM(total_amount) > 3005.06"
                  + ") t",
                    Integer.class, args);
            return n == null ? 0 : n;
        } catch (Exception ex) {
            return 0;
        }
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

        @GetMapping("/portfolio-detailed")
        public List<CompanyBucket> portfolioDetailed() { return service.forPortfolioDetailed(); }
    }
}
