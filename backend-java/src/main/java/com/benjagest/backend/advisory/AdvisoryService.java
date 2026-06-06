package com.benjagest.backend.advisory;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
                       )                AS pending_invitations,
                       (SELECT COUNT(*) FROM advisory_invitations ai2
                         WHERE ai2.advisory_company_id = c.company_id
                           AND ai2.status = 'UNLINKED'
                           AND ((ai2.invited_nif IS NOT NULL AND ai2.invited_nif = c.tax_identifier)
                             OR (ai2.invited_email IS NOT NULL AND ai2.invited_email = pc.email))
                       )                AS unlinked_invitations
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
                        rs.getInt("pending_invitations") > 0,
                        rs.getInt("unlinked_invitations") > 0
                ),
                tenantContext.getCurrentCompanyId());
    }

    /**
     * Vista UI del portfolio: customer + flag de vínculo + invitación
     * pendiente. Reemplaza ManagedClient en la pantalla "Mis clientes".
     *
     * <p>{@code wasUnlinked} indica que existe una invitación previa
     * en estado UNLINKED — es decir, el cliente estuvo vinculado pero
     * desvinculó. Permite a la UI distinguir entre "nunca vinculado"
     * (badge gris) y "estuvo vinculado y rompió" (badge rojo).
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
            boolean hasPendingInvitation,
            boolean wasUnlinked
    ) {
        public boolean isLinked() { return linkedCompanyId != null && !linkedCompanyId.isBlank(); }
    }

    /**
     * Asegura que existe una shadow company gestionada por la asesoría
     * para este customer. Si ya hay una con
     * {@code parent_company_id = asesoría AND tax_identifier = customer.NIF},
     * devuelve su id; si no, la crea.
     *
     * <p>Casos cubiertos:
     * <ul>
     *   <li>Cliente NO vinculado en cartera → la asesoría puede iniciar
     *       gestión: facturas/gastos/asientos/AEAT se guardan bajo la
     *       shadow company. Cuando luego el cliente acepte una invitación,
     *       el merge con su propia company queda para un slice futuro.</li>
     *   <li>Cliente YA vinculado → ya existe una company hija con su NIF;
     *       este método la encuentra y la devuelve (idempotente).</li>
     * </ul>
     *
     * <p>La shadow company se crea con {@code company_type = 'MANAGED_CLIENT'},
     * heredando legal_name/trade_name/tax_identifier del customer. NO se
     * crea membership de usuario — la "propiedad" la mantiene la asesoría
     * a través de {@code parent_company_id}.
     */
    @Transactional
    public String ensureManagedCompany(String customerId) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        // 1) Recoger datos del customer y validar que pertenece a esta asesoría.
        List<CustomerRow> rows = jdbcTemplate.query("""
                SELECT id, legal_name, trade_name, tax_identifier
                  FROM customers
                 WHERE id = ? AND company_id = ? AND active = TRUE
                 LIMIT 1
                """,
                (rs, n) -> new CustomerRow(
                        rs.getString("id"), rs.getString("legal_name"),
                        rs.getString("trade_name"), rs.getString("tax_identifier")),
                customerId, advisoryId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "El cliente no está en tu cartera.");
        }
        CustomerRow c = rows.get(0);

        // 2) ¿Ya hay company hija con ese NIF? Idempotente.
        List<String> existing = jdbcTemplate.query("""
                SELECT id FROM companies
                 WHERE parent_company_id = ?
                   AND tax_identifier = ?
                   AND active = TRUE
                 LIMIT 1
                """,
                (rs, n) -> rs.getString("id"),
                advisoryId, c.taxIdentifier());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        // 3) Crear shadow company.
        String newId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO companies (
                    id, legal_name, trade_name, tax_identifier,
                    company_type, parent_company_id, active
                ) VALUES (?, ?, ?, ?, 'MANAGED_CLIENT', ?, TRUE)
                """,
                newId,
                c.legalName(),
                c.tradeName(),
                c.taxIdentifier(),
                advisoryId);
        return newId;
    }

    private record CustomerRow(String id, String legalName,
                                 String tradeName, String taxIdentifier) {}

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

        /**
         * Inicia la gestión contable de un cliente que aún no está
         * vinculado: crea (o devuelve) una shadow company con su NIF
         * bajo {@code parent_company_id = asesoría}. Devuelve el id de
         * esa company para que la UI haga acting-for y entre al cliente.
         */
        @PostMapping("/{customerId}/start-management")
        public StartManagementResponse startManagement(
                @PathVariable("customerId") String customerId) {
            String companyId = service.ensureManagedCompany(customerId);
            return new StartManagementResponse(companyId);
        }
    }

    public record StartManagementResponse(String companyId) {}
}
