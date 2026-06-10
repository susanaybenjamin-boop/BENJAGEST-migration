package com.benjagest.backend.billing.sif;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import com.benjagest.backend.billing.verifactu.VerifactuConfig;
import com.benjagest.backend.billing.verifactu.VerifactuConfigRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Limpieza puntual de la cadena de eventos SIF de la empresa activa.
 *
 * <p>Pensado para resolver la deuda VF-CHAIN-FIX (2026-06-08): los
 * eventos generados antes de añadir {@code connectionTimeZone=UTC} a
 * la JDBC URL quedaron con hashes inestables que se reportan como
 * rotos por {@link AnomalyDetectionScheduler}. Esta operación BORRA
 * todos los eventos de {@code sif_event_registry} para la empresa
 * activa; el siguiente arranque emite {@code SYSTEM_START} y la cadena
 * empieza limpia y correctamente.
 *
 * <p>Bloqueado para empresas en {@code VERIFACTU}: si la empresa
 * estuviera en modalidad VeriFactu, sus eventos podrían haberse
 * enviado a la AEAT y NO se pueden borrar sin más. Por la Orden
 * HAC/1177/2024 el operador debe conservar la cadena durante el
 * período de prescripción tributaria.
 */
@RestController
@RequestMapping("/api/billing/sif-events")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN"})
public class SifChainCleanupController {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final VerifactuConfigRepository configRepository;

    public SifChainCleanupController(JdbcTemplate jdbcTemplate,
                                      TenantContext tenantContext,
                                      VerifactuConfigRepository configRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.configRepository = configRepository;
    }

    @DeleteMapping("/legacy-chain")
    @Transactional
    public Map<String, Object> resetLegacyChain() {
        String companyId = tenantContext.getCurrentCompanyId();
        VerifactuConfig cfg = configRepository.findCurrent().orElse(null);
        if (cfg != null && "VERIFACTU".equals(cfg.modality())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La empresa está en modalidad VERIFACTU. Borrar la cadena "
                            + "implicaría perder eventos legales ya enviados a la AEAT.");
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM sif_event_registry WHERE company_id = ?",
                companyId);
        return Map.of(
                "deleted", deleted,
                "companyId", companyId,
                "nextStartupWillReseed", true);
    }
}
