package com.benjagest.backend.purchases;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OPTYPE (2026-07-11) — clasificación fiscal de un gasto: tipo de operación
 * (a efectos de IVA/303) y deducibilidad (IVA soportado + gasto IRPF). Se
 * gestiona aparte del alta del gasto (defaults sensatos al crear:
 * INTERIOR, 100% deducible, gasto deducible) para que la asesoría la afine
 * solo en las excepciones (p. ej. un gasto con IVA no deducible).
 *
 * <ul>
 *   <li>GET /api/purchases/invoices/{id}/classification</li>
 *   <li>PUT /api/purchases/invoices/{id}/classification</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/purchases/invoices/{id}/classification")
@RequiresModule("purchases")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class PurchaseClassificationController {

    private static final List<String> OPERATION_TYPES = List.of("INTERIOR", "INTRACOM", "IMPORT", "ISP");

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final PurchaseInvoiceService invoiceService;
    private final PurchaseJournalEntryService journalService;
    private final com.benjagest.backend.auth.CurrentUserService currentUserService;

    public PurchaseClassificationController(JdbcTemplate jdbc, TenantContext tenant,
                                              PurchaseInvoiceService invoiceService,
                                              PurchaseJournalEntryService journalService,
                                              com.benjagest.backend.auth.CurrentUserService currentUserService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.invoiceService = invoiceService;
        this.journalService = journalService;
        this.currentUserService = currentUserService;
    }

    /**
     * DEDUC (2026-07-09): {@code irpfDeductiblePercent} NULL = hereda de la
     * CUENTA del gasto (modelo IRPF-DED); 0-100 = decisión explícita para esta
     * factura. {@code saveAsSupplierRule} TRUE al actualizar guarda/actualiza
     * la regla del proveedor (precarga de futuros gastos de ese NIF).
     */
    public record Classification(String operationType, BigDecimal vatDeductiblePercent,
                                 boolean expenseDeductible, boolean investmentGood,
                                 BigDecimal irpfDeductiblePercent, Boolean saveAsSupplierRule) {}

    @GetMapping
    public Classification get(@PathVariable("id") String id) {
        List<Classification> rows = jdbc.query("""
                SELECT operation_type, vat_deductible_percent, expense_deductible,
                       investment_good, irpf_deductible_percent
                  FROM purchase_invoices WHERE id = ? AND company_id = ?
                """, (rs, n) -> new Classification(
                        rs.getString("operation_type"),
                        rs.getBigDecimal("vat_deductible_percent"),
                        rs.getBoolean("expense_deductible"),
                        rs.getBoolean("investment_good"),
                        rs.getBigDecimal("irpf_deductible_percent"),
                        null),
                id, tenant.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto no encontrado");
        return rows.get(0);
    }

    @PutMapping
    public Classification update(@PathVariable("id") String id, @RequestBody Classification req) {
        // AUDIT-T3: la clasificación (deducibilidad IVA/IRPF) de un gasto cuyo
        // trimestre ya está declarado no se cambia — descuadraría el 303/130
        // presentados. Rectificación en el periodo corriente.
        invoiceService.requirePeriodNotPresented(invoiceService.get(id).invoiceDate(),
                "cambiar la clasificación fiscal de este gasto");
        String op = req.operationType() == null ? "INTERIOR" : req.operationType().trim().toUpperCase();
        if (!OPERATION_TYPES.contains(op)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tipo de operación inválido (INTERIOR/INTRACOM/IMPORT/ISP).");
        }
        BigDecimal pct = req.vatDeductiblePercent() == null ? new BigDecimal("100") : req.vatDeductiblePercent();
        if (pct.signum() < 0 || pct.compareTo(new BigDecimal("100")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "% de IVA deducible fuera de 0-100.");
        }
        BigDecimal irpfPct = req.irpfDeductiblePercent();
        if (irpfPct != null && (irpfPct.signum() < 0 || irpfPct.compareTo(new BigDecimal("100")) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "% de IRPF deducible fuera de 0-100.");
        }
        int n = jdbc.update("""
                UPDATE purchase_invoices
                   SET operation_type = ?, vat_deductible_percent = ?,
                       expense_deductible = ?, investment_good = ?,
                       irpf_deductible_percent = ?
                 WHERE id = ? AND company_id = ?
                """, op, pct, req.expenseDeductible(), req.investmentGood(), irpfPct,
                id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto no encontrado");

        // DEDUC: reflejar el % de IVA deducible en el ASIENTO (criterio PGC:
        // la parte no deducible es mayor gasto en la misma 6xx, el 472 queda
        // solo con la deducible). Total del asiento intacto.
        journalService.applyVatDeductibilitySplit(invoiceService.get(id));

        // DEDUC: aprender la regla del proveedor si el asesor lo pidió.
        if (Boolean.TRUE.equals(req.saveAsSupplierRule())) {
            var inv = invoiceService.get(id);
            if (inv.supplierNif() != null && !inv.supplierNif().isBlank()) {
                String userId;
                try { userId = currentUserService.require().userId(); }
                catch (Exception ex) { userId = null; }
                // Con IRPF NULL (hereda de la cuenta), la regla guarda 100 —
                // la herencia por cuenta ya la cubre la regla NIF→cuenta.
                jdbc.update("""
                        INSERT INTO supplier_deductibility_rules
                            (id, company_id, supplier_nif, vat_deductible_percent,
                             irpf_deductible_percent, created_by_user_id)
                        VALUES (UUID(), ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            vat_deductible_percent = VALUES(vat_deductible_percent),
                            irpf_deductible_percent = VALUES(irpf_deductible_percent)
                        """, tenant.getCurrentCompanyId(),
                        inv.supplierNif().trim().toUpperCase(), pct,
                        irpfPct == null ? new BigDecimal("100") : irpfPct, userId);
            }
        }
        return get(id);
    }

    /**
     * DEDUC fase 2 — gestión de las reglas de deducibilidad por proveedor y
     * export del libro registro de facturas recibidas con las TRES columnas
     * que exige la AEAT (FAQ del libro registro + Orden HAC/773/2019):
     * cuota SOPORTADA, cuota DEDUCIBLE (IVA) e importe deducible (IRPF).
     */
    @RestController
    @RequestMapping("/api/purchases/deductibility")
    @RequiresModule("purchases")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class DeductibilityController {
        private final JdbcTemplate jdbc;
        private final TenantContext tenant;

        public DeductibilityController(JdbcTemplate jdbc, TenantContext tenant) {
            this.jdbc = jdbc;
            this.tenant = tenant;
        }

        public record Rule(String id, String supplierNif, String supplierName,
                           BigDecimal vatPct, BigDecimal irpfPct, String createdAt) {}

        @GetMapping("/rules")
        public List<Rule> rules() {
            return jdbc.query("""
                    SELECT r.id, r.supplier_nif, r.vat_deductible_percent,
                           r.irpf_deductible_percent, r.created_at,
                           (SELECT s.legal_name FROM suppliers s
                             WHERE s.company_id = r.company_id
                               AND s.tax_identifier = r.supplier_nif LIMIT 1) AS supplier_name
                      FROM supplier_deductibility_rules r
                     WHERE r.company_id = ?
                     ORDER BY r.supplier_nif
                    """, (rs, n) -> new Rule(
                            rs.getString("id"), rs.getString("supplier_nif"),
                            rs.getString("supplier_name"),
                            rs.getBigDecimal("vat_deductible_percent"),
                            rs.getBigDecimal("irpf_deductible_percent"),
                            String.valueOf(rs.getTimestamp("created_at"))),
                    tenant.getCurrentCompanyId());
        }

        @org.springframework.web.bind.annotation.DeleteMapping("/rules/{ruleId}")
        @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("ruleId") String ruleId) {
            int n = jdbc.update("""
                    DELETE FROM supplier_deductibility_rules
                     WHERE id = ? AND company_id = ?
                    """, ruleId, tenant.getCurrentCompanyId());
            if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Regla no encontrada");
        }

        /**
         * Libro registro de facturas RECIBIDAS del año en CSV (separador ';',
         * decimales con coma — abre directo en Excel ES). Columnas alineadas
         * con el formato normalizado AEAT: la cuota deducible y el gasto IRPF
         * salen de los % de deducibilidad de cada factura.
         */
        @GetMapping(value = "/libro-recibidas.csv", produces = "text/csv;charset=UTF-8")
        public String libroRecibidas(@org.springframework.web.bind.annotation.RequestParam("year") int year) {
            StringBuilder sb = new StringBuilder();
            sb.append("Fecha;Numero;NIF proveedor;Proveedor;Concepto;")
              .append("Base imponible;Tipo IVA %;Cuota soportada;% IVA deducible;")
              .append("Cuota deducible;% IRPF deducible;Gasto deducible IRPF;Total\n");
            jdbc.query("""
                    SELECT invoice_date, invoice_number, supplier_nif, supplier_name,
                           concept, base_amount, vat_percent, vat_amount, total_amount,
                           COALESCE(vat_deductible_percent, 100) AS vat_pct,
                           COALESCE(irpf_deductible_percent, expense_deductible * 100) AS irpf_pct
                      FROM purchase_invoices
                     WHERE company_id = ? AND status = 'POSTED'
                       AND YEAR(invoice_date) = ?
                     ORDER BY invoice_date, invoice_number
                    """, rs -> {
                        BigDecimal base = nzd(rs.getBigDecimal("base_amount"));
                        BigDecimal vat = nzd(rs.getBigDecimal("vat_amount"));
                        BigDecimal vatPct = nzd(rs.getBigDecimal("vat_pct"));
                        BigDecimal irpfPct = nzd(rs.getBigDecimal("irpf_pct"));
                        BigDecimal cuotaDeducible = vat.multiply(vatPct)
                                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
                        BigDecimal gastoIrpf = base.add(vat.subtract(cuotaDeducible))
                                .multiply(irpfPct)
                                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
                        sb.append(csv(rs.getDate("invoice_date"))).append(';')
                          .append(csv(rs.getString("invoice_number"))).append(';')
                          .append(csv(rs.getString("supplier_nif"))).append(';')
                          .append(csv(rs.getString("supplier_name"))).append(';')
                          .append(csv(rs.getString("concept"))).append(';')
                          .append(dec(base)).append(';')
                          .append(dec(rs.getBigDecimal("vat_percent"))).append(';')
                          .append(dec(vat)).append(';')
                          .append(dec(vatPct)).append(';')
                          .append(dec(cuotaDeducible)).append(';')
                          .append(dec(irpfPct)).append(';')
                          .append(dec(gastoIrpf)).append(';')
                          .append(dec(rs.getBigDecimal("total_amount"))).append('\n');
                    }, tenant.getCurrentCompanyId(), year);
            return sb.toString();
        }

        private static BigDecimal nzd(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
        private static String dec(BigDecimal v) {
            return v == null ? "" : v.setScale(2, java.math.RoundingMode.HALF_UP)
                    .toPlainString().replace('.', ',');
        }
        private static String csv(Object v) {
            if (v == null) return "";
            String s = String.valueOf(v).replace('\n', ' ').replace('\r', ' ');
            return s.contains(";") || s.contains("\"")
                    ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
        }
    }
}
