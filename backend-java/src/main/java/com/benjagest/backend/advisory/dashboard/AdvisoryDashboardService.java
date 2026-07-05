package com.benjagest.backend.advisory.dashboard;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vista panorámica del asesor (cross-client dashboard).
 *
 * <p>Agrega información de varias tablas para dar al OWNER/ACCOUNTANT
 * una visión rápida de:
 * <ul>
 *   <li>Cartera: nº de clientes activos, nº vinculados, nº pendientes
 *       de aceptar invitación.</li>
 *   <li>Obligaciones próximas: modelos AEAT con vencimiento ≤30 días,
 *       contratos que terminan en 30 días, bajas médicas abiertas.</li>
 *   <li>Workflow: notificaciones URGENT/WARNING no leídas, mensajes sin
 *       responder del cliente al asesor.</li>
 *   <li>Carga por empleado (top 5): cuántos clientes lleva asignados
 *       cada miembro del equipo. Útil para reasignar y prevenir
 *       sobrecargas.</li>
 * </ul>
 *
 * <p>Toda la información se calcula con SQL agregada sobre las tablas
 * existentes — sin nuevas migraciones. Performance pensado para que
 * cargue en &lt;200ms incluso con 200 clientes (cada cuenta es un
 * COUNT con índices ya existentes).
 */
@Service
public class AdvisoryDashboardService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public AdvisoryDashboardService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public DashboardSnapshot snapshot() {
        String advisoryId = tenant.getCurrentCompanyId();
        return new DashboardSnapshot(
                cartera(advisoryId),
                obligaciones(advisoryId),
                workflow(advisoryId),
                workloadByEmployee(advisoryId)
        );
    }

    /**
     * PANORAMA-ASESORIA — KPIs agregados de TODA la cartera del asesor.
     * Suma facturación y cobros de todos los clientes (CLIENT con
     * parent_company_id = la asesoría).
     */
    public PortfolioFinancials portfolioFinancials() {
        String advisoryId = tenant.getCurrentCompanyId();
        // Total facturado este mes (suma de totals de facturas VALIDATED).
        java.math.BigDecimal billed = bigDecimalOrZero("""
                SELECT COALESCE(SUM(si.total), 0)
                  FROM sales_invoices si
                  JOIN companies c ON c.id = si.company_id
                 WHERE c.parent_company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.invoice_date >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                """, advisoryId);
        // Pendiente cobro: total - paid_amount donde payment_status != PAID.
        // Se excluyen rectificativas que anulan una original ya VOIDED (el par
        // netea a cero; dejar solo la negativa restaba de mas). Ver
        // ClientFinancialsService.pendingCollections para el detalle del bug.
        java.math.BigDecimal pending = bigDecimalOrZero("""
                SELECT COALESCE(SUM(si.total - COALESCE(si.paid_amount, 0)), 0)
                  FROM sales_invoices si
                  JOIN companies c ON c.id = si.company_id
                 WHERE c.parent_company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.payment_status IN ('PENDING', 'PARTIAL')
                   AND NOT (si.original_invoice_id IS NOT NULL AND EXISTS (
                            SELECT 1 FROM sales_invoices o
                             WHERE o.id = si.original_invoice_id AND o.status = 'VOIDED'))
                """, advisoryId);
        // Facturas vencidas (due_date < today, no pagadas).
        Integer overdueCount = countOrZero("""
                SELECT COUNT(*)
                  FROM sales_invoices si
                  JOIN companies c ON c.id = si.company_id
                 WHERE c.parent_company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.payment_status IN ('PENDING', 'PARTIAL')
                   AND si.due_date IS NOT NULL
                   AND si.due_date < CURRENT_DATE
                   AND NOT (si.original_invoice_id IS NOT NULL AND EXISTS (
                            SELECT 1 FROM sales_invoices o
                             WHERE o.id = si.original_invoice_id AND o.status = 'VOIDED'))
                """, advisoryId);
        // Clientes activos con al menos 1 factura este mes.
        Integer activeClients = countOrZero("""
                SELECT COUNT(DISTINCT si.company_id)
                  FROM sales_invoices si
                  JOIN companies c ON c.id = si.company_id
                 WHERE c.parent_company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.invoice_date >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                """, advisoryId);
        // Pendientes de aprobación TPB-3
        Integer pendingApprovals = countOrZero("""
                SELECT COUNT(*) FROM sales_invoices si
                  JOIN companies c ON c.id = si.company_id
                 WHERE c.parent_company_id = ?
                   AND si.status = 'PENDING_CLIENT_APPROVAL'
                """, advisoryId);
        return new PortfolioFinancials(billed, pending, overdueCount,
                activeClients, pendingApprovals);
    }

    private java.math.BigDecimal bigDecimalOrZero(String sql, Object... args) {
        try {
            java.math.BigDecimal v = jdbc.queryForObject(sql,
                    java.math.BigDecimal.class, args);
            return v == null ? java.math.BigDecimal.ZERO : v;
        } catch (Exception ex) {
            return java.math.BigDecimal.ZERO;
        }
    }

    public record PortfolioFinancials(
            java.math.BigDecimal billedThisMonth,
            java.math.BigDecimal pendingPayment,
            int overdueInvoices,
            int activeClientsThisMonth,
            int pendingTpbApprovals
    ) {}

    private CarteraStats cartera(String advisoryId) {
        // Customers vinculados/no vinculados de la cartera. Definimos
        // 'vinculado' como tener invitación ACCEPTED vigente; el resto
        // se considera no vinculado.
        Integer total = countOrZero("""
                SELECT COUNT(*) FROM customers WHERE company_id = ? AND active = TRUE
                """, advisoryId);
        Integer linked = countOrZero("""
                SELECT COUNT(DISTINCT c.id)
                  FROM customers c
                  JOIN advisory_invitations ai ON
                       (ai.invited_nif IS NOT NULL AND ai.invited_nif = c.tax_identifier)
                       AND ai.advisory_company_id = c.company_id
                       AND ai.status = 'ACCEPTED'
                 WHERE c.company_id = ? AND c.active = TRUE
                """, advisoryId);
        Integer pendingInvitations = countOrZero("""
                SELECT COUNT(*) FROM advisory_invitations
                 WHERE advisory_company_id = ? AND status = 'PENDING'
                   AND expires_at > CURRENT_TIMESTAMP
                """, advisoryId);
        return new CarteraStats(total, linked, total - linked, pendingInvitations);
    }

    private ObligacionesStats obligaciones(String advisoryId) {
        // Modelos AEAT vencen en ≤30 días. La columna periodMonth + año
        // no nos da fecha exacta — usamos un proxy aproximado. Si no
        // hay tax_filings registradas, simplemente devolvemos 0.
        Integer taxFilingsDueSoon = countOrZero("""
                SELECT COUNT(*) FROM tax_filings tf
                  JOIN companies c ON c.id = tf.company_id
                 WHERE c.parent_company_id = ? AND tf.status = 'PENDING'
                   AND tf.due_date IS NOT NULL
                   AND tf.due_date BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY)
                """, advisoryId);
        // Contratos del cliente que terminan en 30 días.
        Integer contractsExpiring = countOrZero("""
                SELECT COUNT(*) FROM employment_contracts ec
                  JOIN companies c ON c.id = ec.company_id
                 WHERE c.parent_company_id = ?
                   AND ec.end_date IS NOT NULL
                   AND ec.end_date BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY)
                """, advisoryId);
        // Bajas médicas abiertas en cualquier cliente.
        Integer medicalLeavesOpen = countOrZero("""
                SELECT COUNT(*) FROM medical_leaves ml
                  JOIN companies c ON c.id = ml.company_id
                 WHERE c.parent_company_id = ?
                   AND ml.status = 'OPEN'
                """, advisoryId);
        return new ObligacionesStats(taxFilingsDueSoon, contractsExpiring, medicalLeavesOpen);
    }

    private WorkflowStats workflow(String advisoryId) {
        Integer urgentNotis = countOrZero("""
                SELECT COUNT(*) FROM advisory_notifications
                 WHERE advisory_company_id = ?
                   AND severity = 'URGENT'
                   AND read_at IS NULL AND dismissed_at IS NULL
                """, advisoryId);
        Integer warningNotis = countOrZero("""
                SELECT COUNT(*) FROM advisory_notifications
                 WHERE advisory_company_id = ?
                   AND severity = 'WARNING'
                   AND read_at IS NULL AND dismissed_at IS NULL
                """, advisoryId);
        // Mensajes del cliente al asesor sin leer.
        Integer unreadIncoming = countOrZero("""
                SELECT COUNT(*) FROM advisory_messages
                 WHERE advisory_company_id = ?
                   AND direction = 'C2A' AND read_at IS NULL
                """, advisoryId);
        // Documentos del cliente pendientes de revisión.
        Integer pendingDocs = countOrZero("""
                SELECT COUNT(*) FROM advisory_documents
                 WHERE advisory_company_id = ?
                   AND direction = 'C2A' AND status = 'UPLOADED'
                """, advisoryId);
        return new WorkflowStats(urgentNotis, warningNotis, unreadIncoming, pendingDocs);
    }

    private List<EmployeeWorkload> workloadByEmployee(String advisoryId) {
        // Top 5 empleados por nº de clientes asignados activos.
        return jdbc.query("""
                SELECT ca.employee_user_id AS user_id,
                       u.display_name AS display_name,
                       COUNT(DISTINCT ca.client_company_id) AS client_count
                  FROM client_assignments ca
                  JOIN user_accounts u ON u.id = ca.employee_user_id
                 WHERE ca.advisory_company_id = ?
                   AND ca.active = TRUE
                 GROUP BY ca.employee_user_id, u.display_name
                 ORDER BY client_count DESC
                 LIMIT 5
                """,
                (rs, i) -> new EmployeeWorkload(
                        rs.getString("user_id"),
                        rs.getString("display_name"),
                        rs.getInt("client_count")
                ),
                advisoryId);
    }

    /**
     * Helper: ejecuta un COUNT y devuelve 0 si el query falla. Esto nos
     * permite que el dashboard cargue aunque alguna tabla nueva (ej.
     * advisory_notifications) no exista en una BD legacy sin las
     * migraciones aplicadas.
     */
    private Integer countOrZero(String sql, Object... args) {
        try {
            Integer n = jdbc.queryForObject(sql, Integer.class, args);
            return n == null ? 0 : n;
        } catch (Exception ex) {
            return 0;
        }
    }

    // ============================================================
    //  DTOs
    // ============================================================

    public record DashboardSnapshot(
            CarteraStats cartera,
            ObligacionesStats obligaciones,
            WorkflowStats workflow,
            List<EmployeeWorkload> workload
    ) {
        /** Mapa de salida 'plano' para serialización rápida en UI. */
        public Map<String, Object> flat() {
            return Map.of(
                    "cartera", cartera,
                    "obligaciones", obligaciones,
                    "workflow", workflow,
                    "workload", workload,
                    "generatedAt", java.time.Instant.now().toString()
            );
        }
    }

    public record CarteraStats(
            int totalCustomers,
            int linkedCustomers,
            int unlinkedCustomers,
            int pendingInvitations
    ) {}

    public record ObligacionesStats(
            int taxFilingsDueSoon,
            int contractsExpiringSoon,
            int medicalLeavesOpen
    ) {}

    public record WorkflowStats(
            int urgentNotifications,
            int warningNotifications,
            int unreadIncomingMessages,
            int pendingDocsForReview
    ) {}

    public record EmployeeWorkload(
            String userId,
            String displayName,
            int clientCount
    ) {}

    @RestController
    @RequestMapping("/api/advisory/dashboard")
    @RequiresModule("advisory")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final AdvisoryDashboardService service;

        public Controller(AdvisoryDashboardService service) {
            this.service = service;
        }

        @GetMapping
        public DashboardSnapshot snapshot() {
            return service.snapshot();
        }

        @GetMapping("/portfolio-financials")
        public PortfolioFinancials portfolioFinancials() {
            return service.portfolioFinancials();
        }
    }
}
