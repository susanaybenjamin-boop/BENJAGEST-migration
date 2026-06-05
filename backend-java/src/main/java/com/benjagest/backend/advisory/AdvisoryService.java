package com.benjagest.backend.advisory;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listado de clientes gestionados por la asesoria actual.
 *
 * Modelo: parent_company_id en companies apunta a la asesoria (V26).
 * Una asesoria es una empresa con company_type=INTERNAL o ADVISORY;
 * sus clientes son empresas con parent_company_id = asesoria.id.
 *
 * Switch de TenantContext: el frontend, al seleccionar un cliente,
 * empieza a mandar X-Company-Id = client.id en sus peticiones. El
 * RequestScopedTenantContext lo aplica; el backend opera contra ese
 * tenant para esa peticion sin ningun cambio extra. La asesoria
 * mantiene su sesion JWT.
 *
 * Defensa: este endpoint solo lista clientes cuyo parent_company_id
 * == tenant actual (= la asesoria que esta haciendo la query). Una
 * asesoria no ve clientes de otra asesoria aunque tenga su id.
 */
@Service
public class AdvisoryService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public AdvisoryService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<ManagedClient> listMyManagedClients() {
        return jdbcTemplate.query("""
                SELECT id, legal_name, trade_name, tax_identifier,
                       company_type, email, phone, city, province
                  FROM companies
                 WHERE parent_company_id = ?
                 ORDER BY legal_name
                """,
                this::mapClient,
                tenantContext.getCurrentCompanyId()
        );
    }

    /**
     * Portfolio unificado de la asesoría: muestra TODOS sus customers
     * (cartera) con un flag indicando si están vinculados como
     * managed clients (parent_company_id apunta a esta asesoría) y si
     * tienen una invitación pendiente.
     *
     * <p>Esta es la nueva "Mis clientes" — fusiona cartera y vínculos
     * en una sola lista para que la asesoría pueda facturar a todos
     * sus clientes (vinculados o no) y vincular los que aún no lo
     * estén con un click.
     */
    public List<CustomerPortfolioEntry> listPortfolio() {
        return jdbcTemplate.query("""
                SELECT c.id            AS customer_id,
                       c.legal_name,
                       c.trade_name,
                       c.tax_identifier,
                       c.customer_type,
                       pc.email,
                       pc.phone,
                       cp.id            AS linked_company_id,
                       cp.city,
                       (SELECT COUNT(*) FROM advisory_invitations ai
                         WHERE ai.advisory_company_id = c.company_id
                           AND ai.status = 'PENDING'
                           AND ai.expires_at > CURRENT_TIMESTAMP
                           AND ((ai.invited_nif IS NOT NULL AND ai.invited_nif = c.tax_identifier)
                             OR (ai.invited_email IS NOT NULL AND ai.invited_email = pc.email))
                       )                AS pending_invitations
                  FROM customers c
                  LEFT JOIN customer_contacts pc
                         ON pc.customer_id = c.id
                        AND pc.primary_contact = TRUE
                        AND pc.active = TRUE
                  LEFT JOIN companies cp
                         ON cp.tax_identifier = c.tax_identifier
                        AND cp.parent_company_id = c.company_id
                 WHERE c.active = TRUE
                   AND c.company_id = ?
                 ORDER BY c.legal_name
                """,
                (rs, n) -> new CustomerPortfolioEntry(
                        rs.getString("customer_id"),
                        rs.getString("legal_name"),
                        rs.getString("trade_name"),
                        rs.getString("tax_identifier"),
                        rs.getString("customer_type"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("city"),
                        rs.getString("linked_company_id"),
                        rs.getInt("pending_invitations") > 0
                ),
                tenantContext.getCurrentCompanyId());
    }

    /**
     * Vista UI del portfolio: customer + flag de vínculo + invitación
     * pendiente. Reemplaza ManagedClient en la pantalla "Mis clientes".
     */
    public record CustomerPortfolioEntry(
            String customerId,
            String legalName,
            String tradeName,
            String taxIdentifier,
            String customerType,
            String email,
            String phone,
            String city,
            String linkedCompanyId,
            boolean hasPendingInvitation
    ) {
        public boolean isLinked() { return linkedCompanyId != null && !linkedCompanyId.isBlank(); }
    }

    private ManagedClient mapClient(ResultSet rs, int rowNum) throws SQLException {
        return new ManagedClient(
                rs.getString("id"),
                rs.getString("legal_name"),
                rs.getString("trade_name"),
                rs.getString("tax_identifier"),
                rs.getString("company_type"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("city"),
                rs.getString("province")
        );
    }

    @RestController
    @RequestMapping("/api/advisory/clients")
    @RequiresModule("advisory")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class AdvisoryController {
        private final AdvisoryService service;

        public AdvisoryController(AdvisoryService service) {
            this.service = service;
        }

        @GetMapping
        public List<ManagedClient> listClients() {
            return service.listMyManagedClients();
        }

        @GetMapping("/portfolio")
        public List<CustomerPortfolioEntry> listPortfolio() {
            return service.listPortfolio();
        }
    }
}
