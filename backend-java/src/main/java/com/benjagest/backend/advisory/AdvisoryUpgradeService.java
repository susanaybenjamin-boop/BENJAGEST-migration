package com.benjagest.backend.advisory;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * MODE-UP — Sube una cuenta de EMPRESARIO a ASESORIA.
 *
 * <p>El "modo" de BENJAGEST no es otra base de datos ni otro arbol de datos:
 * son dos cosas, {@code companies.company_type} ('CLIENT' vs 'ADVISORY') y los
 * modulos activos de la empresa. Todo lo demas (facturas, diario, empleados,
 * clientes, certificados) cuelga de {@code company_id}, que NO cambia aqui. Por
 * eso subir de modo no mueve ni un dato: cambia el tipo, enciende los modulos
 * que le faltan y crea el self-link con el que la asesoria se gestiona a si
 * misma ("Mi gestion").
 *
 * <p>Diseno: {@code docs/design-subida-empresario-a-asesoria.md}.
 *
 * <p>La empresa que se sube es la del JWT ({@code activeCompanyId}), <b>nunca</b>
 * el {@code TenantContext}: el OWNER puede estar actuando como uno de sus
 * clientes cuando pulsa el boton, y lo que debe subir es SU empresa
 * (CLAUDE.md §5).
 */
@Service
public class AdvisoryUpgradeService {

    /**
     * Modulos que necesita una asesoria y un empresario no tiene.
     *
     * <p>{@code advisory} es el que manda: sin el, {@code @RequiresModule} corta
     * {@code /api/advisory/clients} con un 403 y la asesoria no ve ni su cartera
     * ni "Mi gestion" (que entra por ahi). Lo descubrio el smoke de ejecucion de
     * MODE-UP: ese modulo solo se activaba en el seed V8, asi que ni un alta de
     * asesoria por {@code /api/auth/register} ni una conversion lo encendian.
     *
     * <p>{@code tax} (Modelos AEAT) y {@code notifications} (buzon) son los que
     * {@code RegisterService} da de alta a una asesoria y no a un empresario, y
     * {@code team} es el reparto de clientes entre empleados (V69).
     */
    private static final List<String> ADVISORY_MODULES =
            List.of("advisory", "tax", "notifications", "team");

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final AuditService auditService;

    public AdvisoryUpgradeService(JdbcTemplate jdbc, CurrentUserService currentUser,
                                  AuditService auditService) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.auditService = auditService;
    }

    /** Lo que la UI necesita saber despues de subir. */
    public record UpgradeResult(String companyId, String companyType,
                                List<String> activatedModules) {
    }

    /**
     * Convierte la empresa activa del usuario en ASESORIA.
     *
     * <p>Las guardas van ANTES de cualquier escritura, para que un rechazo no
     * pueda dejar la empresa a medias.
     *
     * @throws ResponseStatusException 404 {@code COMPANY_NOT_FOUND}; 409
     *         {@code LINKED_TO_ADVISORY} si es cliente de otra asesoria; 409
     *         {@code ALREADY_ADVISORY} si no es una empresa de empresario.
     */
    @Transactional
    public UpgradeResult upgradeActiveCompany() {
        AuthenticatedUser user = currentUser.require();
        String companyId = user.activeCompanyId();
        if (companyId == null || companyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND");
        }

        List<Company> rows = jdbc.query(
                "SELECT company_type, parent_company_id FROM companies WHERE id = ?",
                (rs, n) -> new Company(rs.getString("company_type"),
                        rs.getString("parent_company_id")),
                companyId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND");
        }
        // Vinculada a otra asesoria: convertirla le quitaria el modulo
        // Comunicacion con su gestor (ModuleRepository#hasCommLink cuenta hijos
        // CLIENT, y una empresa recien convertida no tiene ninguno). Primero hay
        // que desvincularse.
        if (rows.get(0).parentCompanyId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LINKED_TO_ADVISORY");
        }

        int updated = jdbc.update("""
                UPDATE companies
                   SET company_type = 'ADVISORY'
                 WHERE id = ? AND company_type = 'CLIENT'
                """, companyId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_ADVISORY");
        }

        activateAdvisoryModules(companyId);
        ensureSelfLink(companyId);

        auditService.recordGeneric(companyId, user.userId(), "COMPANY_UPGRADED_TO_ADVISORY",
                "company", companyId, "OK", null);

        return new UpgradeResult(companyId, "ADVISORY", ADVISORY_MODULES);
    }

    /**
     * Enciende los modulos de asesoria. ADITIVO: nunca apaga nada de lo que el
     * empresario ya tuviera activo (anti-objetivo del diseno).
     */
    private void activateAdvisoryModules(String companyId) {
        for (String slug : ADVISORY_MODULES) {
            jdbc.update("""
                    INSERT INTO company_modules (id, company_id, module_id, active,
                                                 activated_at, activated_by)
                    SELECT UUID(), ?, m.id, TRUE, CURRENT_TIMESTAMP, NULL
                      FROM module_catalog m
                     WHERE m.slug = ? AND m.active_in_catalog = TRUE
                    ON DUPLICATE KEY UPDATE active = TRUE,
                                            deactivated_at = NULL,
                                            deactivated_by = NULL
                    """, companyId, slug);
        }
    }

    /**
     * Autovinculacion de la asesoria a si misma: la fila SELF de
     * {@code advisory_invitations} es lo que hace que "Mi gestion" encuentre la
     * propia empresa (V64 / {@link AdvisoryService#ensureSelfLink}). Misma SQL,
     * pero con el companyId por parametro: aqui no se puede depender del
     * TenantContext.
     */
    private void ensureSelfLink(String companyId) {
        Integer already = jdbc.queryForObject("""
                SELECT COUNT(*) FROM advisory_invitations
                 WHERE advisory_company_id = ?
                   AND invited_company_id  = ?
                   AND status = 'ACCEPTED'
                """, Integer.class, companyId, companyId);
        if (already != null && already > 0) return;
        jdbc.update("""
                INSERT INTO advisory_invitations (
                    id, advisory_company_id, invited_email, invited_nif,
                    invited_company_name, invited_company_id, token, status,
                    expires_at, notes, accepted_at, created_at, updated_at
                )
                SELECT UUID(), c.id, NULL, c.tax_identifier,
                       COALESCE(c.trade_name, 'Mi empresa'), c.id,
                       CONCAT('SELF:', c.id), 'ACCEPTED',
                       '2038-01-01 00:00:00',
                       'Auto-vinculacion silenciosa (MODE-UP). NO BORRAR.',
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                  FROM companies c
                 WHERE c.id = ?
                """, companyId);
    }

    private record Company(String companyType, String parentCompanyId) {
    }
}
