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
    }
}
