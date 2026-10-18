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
    private final com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService reflectionService;
    private final com.benjagest.backend.auth.CurrentUserService currentUserService;
    private final com.benjagest.backend.billing.tpb.BillingAgreementGuard billingAgreementGuard;
    private final com.benjagest.backend.accounting.PaymentScheduleService paymentSchedule;

    public MultiAllocationPaymentService(JdbcTemplate jdbc, TenantContext tenant,
            com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService reflectionService,
            com.benjagest.backend.auth.CurrentUserService currentUserService,
            com.benjagest.backend.billing.tpb.BillingAgreementGuard billingAgreementGuard,
            com.benjagest.backend.accounting.PaymentScheduleService paymentSchedule) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.reflectionService = reflectionService;
        this.currentUserService = currentUserService;
        this.billingAgreementGuard = billingAgreementGuard;
        this.paymentSchedule = paymentSchedule;
    }

    /**
     * COB-MULTI — método de "Cobrar varias" → método del vencimiento (la UI de
     * Vencimientos traduce {@code duedates.method.*}: TRANSFER/CASH/CARD/BIZUM/OTHER).
     */
    static String dueDateMethod(String method) {
        if (method == null) return "TRANSFER";
        return switch (method) {
            case "BANK_TRANSFER", "DIRECT_DEBIT" -> "TRANSFER";
            case "CASH" -> "CASH";
            case "CARD" -> "CARD";
            default -> "OTHER";
        };
    }

    @Transactional
    public PaymentRegistrationResult registerMultiAllocation(MultiAllocationRequest req) {
        // AGR-1: cobro de facturas de venta → exige acuerdo de facturación.
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.SALES);
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
                    SELECT total, paid_amount, status, invoice_number
                      FROM sales_invoices
                     WHERE id = ? AND company_id = ?
                    """, rs -> {
                if (rs.next()) {
                    return new Object[]{rs.getBigDecimal("total"),
                            rs.getBigDecimal("paid_amount"),
                            rs.getString("status"),
                            rs.getString("invoice_number")};
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
            // COB-MULTI (2026-10-18) — el cobro va por VENCIMIENTOS, el mismo
            // camino que el diálogo de Vencimientos: asiento 572/430 (570 si es
            // en efectivo), vencimiento saldado, reflejo en el cliente y estado
            // de la factura proyectado desde los vencimientos. Antes solo se
            // apuntaba aquí y se ponía la factura PAGADA a mano: sin asiento, y
            // Vencimientos la seguía viendo PENDIENTE (Benjamin, smoke 0.1.66).
            String number = (String) invoice[3];
            String concept = "Cobro fra. " + (number == null ? "" : number)
                    + (req.reference() == null || req.reference().isBlank()
                        ? "" : " - " + req.reference().trim());
            List<String> settled = paymentSchedule.settleAmount("SALES", alloc.invoiceId(),
                    alloc.amount().setScale(2, RoundingMode.HALF_UP),
                    new com.benjagest.backend.accounting.PaymentScheduleService.PayRequest(
                            "CASH".equals(req.paymentMethod()) ? "570" : "572",
                            req.paymentDate(), dueDateMethod(req.paymentMethod()), concept));
            // Registro del cobro: una fila POR VENCIMIENTO saldado, todas con el
            // mismo payment_id (identifican la transferencia en el extracto) y
            // enlazadas al vencimiento, para que "Deshacer pago" las borre.
            for (String dueDateId : settled) {
                BigDecimal ddAmount = jdbc.queryForObject(
                        "SELECT amount FROM invoice_due_dates WHERE id = ?", BigDecimal.class, dueDateId);
                jdbc.update("""
                        INSERT INTO sales_invoice_payments
                               (id, invoice_id, payment_date, amount, payment_method,
                                reference, notes, payment_id, due_date_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID().toString(),
                        alloc.invoiceId(),
                        java.sql.Date.valueOf(req.paymentDate()),
                        ddAmount,
                        req.paymentMethod(), req.reference(), req.notes(),
                        paymentId, dueDateId);
            }
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
