package com.benjagest.backend.billing.payments;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * MULTI-ALLOCATION (V102) — registra un pago real que se reparte
 * entre varias facturas. Genera un payment_id común a todas las
 * filas insertadas en sales_invoice_payments para que el extracto
 * bancario las identifique como una misma transferencia.
 *
 * <p>Valida que la suma de allocations = totalAmount y que las
 * facturas pertenecen a la empresa actual + tienen pendiente al
 * menos el importe que se les asigna.
 */
@Service
public class MultiAllocationPaymentService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public MultiAllocationPaymentService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional
    public PaymentRegistrationResult registerMultiAllocation(MultiAllocationRequest req) {
        if (req.allocations() == null || req.allocations().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "allocations no puede estar vacío");
        }
        if (req.totalAmount() == null || req.totalAmount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "totalAmount > 0 obligatorio");
        }
        if (req.paymentDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "paymentDate obligatorio");
        }
        BigDecimal sum = req.allocations().stream()
                .map(Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.setScale(2, RoundingMode.HALF_UP)
                .compareTo(req.totalAmount().setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La suma de allocations (" + sum + ") no coincide con totalAmount ("
                            + req.totalAmount() + ")");
        }
        String companyId = tenant.getCurrentCompanyId();
        String paymentId = UUID.randomUUID().toString();
        for (Allocation alloc : req.allocations()) {
            if (alloc.invoiceId() == null || alloc.invoiceId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "allocation.invoiceId obligatorio");
            }
            if (alloc.amount() == null || alloc.amount().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "allocation.amount > 0 obligatorio");
            }
            // Verificar factura pertenece a empresa + estado válido para pago
            var invoice = jdbc.query("""
                    SELECT total, paid_amount, status
                      FROM sales_invoices
                     WHERE id = ? AND company_id = ?
                    """, rs -> {
                if (rs.next()) {
                    return new Object[]{rs.getBigDecimal("total"),
                            rs.getBigDecimal("paid_amount"),
                            rs.getString("status")};
                }
                return null;
            }, alloc.invoiceId(), companyId);
            if (invoice == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Factura no encontrada: " + alloc.invoiceId());
            }
            BigDecimal total = (BigDecimal) invoice[0];
            BigDecimal paid  = (BigDecimal) invoice[1];
            String status = (String) invoice[2];
            if (!"VALIDATED".equals(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Factura " + alloc.invoiceId() + " no está VALIDATED (estado "
                                + status + ")");
            }
            BigDecimal pending = total.subtract(paid == null ? BigDecimal.ZERO : paid);
            if (alloc.amount().compareTo(pending) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Importe " + alloc.amount() + " excede pendiente "
                                + pending + " de la factura " + alloc.invoiceId());
            }
            // Insertar la allocation
            jdbc.update("""
                    INSERT INTO sales_invoice_payments
                           (id, invoice_id, payment_date, amount, payment_method,
                            reference, notes, payment_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    alloc.invoiceId(),
                    java.sql.Date.valueOf(req.paymentDate()),
                    alloc.amount().setScale(2, RoundingMode.HALF_UP),
                    req.paymentMethod(), req.reference(), req.notes(),
                    paymentId);
            // Recalcular paid_amount + payment_status
            BigDecimal newPaid = (paid == null ? BigDecimal.ZERO : paid).add(alloc.amount());
            String newStatus;
            int cmp = newPaid.compareTo(total);
            if (cmp >= 0) newStatus = "PAID";
            else if (newPaid.signum() > 0) newStatus = "PARTIAL";
            else newStatus = "PENDING";
            jdbc.update("""
                    UPDATE sales_invoices
                       SET paid_amount = ?, payment_status = ?
                     WHERE id = ? AND company_id = ?
                    """, newPaid, newStatus, alloc.invoiceId(), companyId);
        }
        return new PaymentRegistrationResult(paymentId, req.allocations().size(),
                req.totalAmount());
    }

    public record MultiAllocationRequest(
            LocalDate paymentDate,
            BigDecimal totalAmount,
            String paymentMethod,
            String reference,
            String notes,
            List<Allocation> allocations
    ) {}

    public record Allocation(String invoiceId, BigDecimal amount) {}

    public record PaymentRegistrationResult(
            String paymentId, int allocationsCount, BigDecimal totalAmount) {}

    @RestController
    @RequestMapping("/api/billing/payments")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final MultiAllocationPaymentService service;

        public Controller(MultiAllocationPaymentService service) {
            this.service = service;
        }

        @PostMapping("/multi-allocation")
        public PaymentRegistrationResult registerMultiAllocation(
                @RequestBody MultiAllocationRequest req) {
            return service.registerMultiAllocation(req);
        }
    }
}
