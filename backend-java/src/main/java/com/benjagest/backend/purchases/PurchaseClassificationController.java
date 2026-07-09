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

    public PurchaseClassificationController(JdbcTemplate jdbc, TenantContext tenant,
                                              PurchaseInvoiceService invoiceService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.invoiceService = invoiceService;
    }

    public record Classification(String operationType, BigDecimal vatDeductiblePercent,
                                 boolean expenseDeductible, boolean investmentGood) {}

    @GetMapping
    public Classification get(@PathVariable("id") String id) {
        List<Classification> rows = jdbc.query("""
                SELECT operation_type, vat_deductible_percent, expense_deductible, investment_good
                  FROM purchase_invoices WHERE id = ? AND company_id = ?
                """, (rs, n) -> new Classification(
                        rs.getString("operation_type"),
                        rs.getBigDecimal("vat_deductible_percent"),
                        rs.getBoolean("expense_deductible"),
                        rs.getBoolean("investment_good")),
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
        int n = jdbc.update("""
                UPDATE purchase_invoices
                   SET operation_type = ?, vat_deductible_percent = ?,
                       expense_deductible = ?, investment_good = ?
                 WHERE id = ? AND company_id = ?
                """, op, pct, req.expenseDeductible(), req.investmentGood(),
                id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto no encontrado");
        return get(id);
    }
}
