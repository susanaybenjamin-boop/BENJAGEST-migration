package com.benjagest.backend.billing.invoices;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
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
 * OPTYPE-3 (2026-07-08) — clasificación del tipo de operación (a efectos de
 * IVA/349) de una factura EMITIDA. Espejo del de compras, pero las ventas no
 * tienen deducibilidad: solo el tipo de operación. Sirve para marcar una
 * entrega intracomunitaria (clave E del modelo 349).
 *
 * <ul>
 *   <li>GET /api/billing/invoices/{id}/classification</li>
 *   <li>PUT /api/billing/invoices/{id}/classification</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/billing/invoices/{id}/classification")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class SalesClassificationController {

    private static final List<String> OPERATION_TYPES = List.of("INTERIOR", "INTRACOM", "IMPORT", "ISP");

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public SalesClassificationController(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public record Classification(String operationType) {}

    @GetMapping
    public Classification get(@PathVariable("id") String id) {
        List<Classification> rows = jdbc.query("""
                SELECT operation_type FROM sales_invoices WHERE id = ? AND company_id = ?
                """, (rs, n) -> new Classification(rs.getString("operation_type")),
                id, tenant.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no encontrada");
        return rows.get(0);
    }

    @PutMapping
    public Classification update(@PathVariable("id") String id, @RequestBody Classification req) {
        String op = req.operationType() == null ? "INTERIOR" : req.operationType().trim().toUpperCase();
        if (!OPERATION_TYPES.contains(op)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tipo de operación inválido (INTERIOR/INTRACOM/IMPORT/ISP).");
        }
        int n = jdbc.update("""
                UPDATE sales_invoices SET operation_type = ? WHERE id = ? AND company_id = ?
                """, op, id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no encontrada");
        return get(id);
    }
}
