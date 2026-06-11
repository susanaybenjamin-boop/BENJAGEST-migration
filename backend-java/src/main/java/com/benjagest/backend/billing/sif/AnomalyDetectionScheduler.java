package com.benjagest.backend.billing.sif;

import com.benjagest.backend.billing.verifactu.VerifactuRegistryRepository;
import com.benjagest.backend.billing.verifactu.VerifactuRegistryService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detector de anomalias en las cadenas hash — slice VF-ANOMALY.
 *
 * Cumple los eventos 3 a 6 de la lista obligatoria de la Orden
 * HAC/1177/2024 art. 16:
 *
 *   3. ANOMALY_DETECTION_INVOICES_RUN   — se lanza la deteccion en
 *                                          registros de facturacion.
 *   4. ANOMALY_DETECTION_INVOICES_HIT   — se ha encontrado anomalia.
 *   5. ANOMALY_DETECTION_EVENTS_RUN     — se lanza la deteccion en
 *                                          registros de eventos.
 *   6. ANOMALY_DETECTION_EVENTS_HIT     — se ha encontrado anomalia.
 *
 * <p>Disparado 2026-06-11 (decisión Benjamin alineado con CONTENDO):
 * - Al arranque del programa (tras SYSTEM_START en SifEventLifecycle).
 * - Al cierre del programa (antes de SUMMARY_SHUTDOWN en SifEventLifecycle).
 * - Bajo demanda desde UI Configuración → Auditoría → "Verificar ahora".
 *
 * <p>Antes había un {@code @Scheduled(fixedDelay = 12h)} que saturaba
 * la BD con eventos RUN aunque nadie usase el programa. Se eliminó por
 * dos motivos: la Orden no obliga periodicidad concreta, y la integridad
 * solo importa cuando hay actividad real (que coincide con que el
 * programa esté arrancado).
 *
 * Iteramos por (companyId, mode) usando findAllChainRefs, asi cada
 * cadena se verifica una vez. Para los eventos SIF la cadena es
 * unica por empresa (sin mode), asi que iteramos las empresas
 * NO_VERIFACTU una sola vez por ciclo.
 */
@Component
public class AnomalyDetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionScheduler.class);

    private final VerifactuRegistryRepository registryRepository;
    private final VerifactuRegistryService verifactuRegistryService;
    private final SifEventService sifEventService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.benjagest.backend.advisory.notifications.AdvisoryNotificationService notificationService;

    public AnomalyDetectionScheduler(VerifactuRegistryRepository registryRepository,
                                      VerifactuRegistryService verifactuRegistryService,
                                      SifEventService sifEventService,
                                      org.springframework.jdbc.core.JdbcTemplate jdbc,
                                      com.benjagest.backend.advisory.notifications.AdvisoryNotificationService notificationService) {
        this.registryRepository = registryRepository;
        this.verifactuRegistryService = verifactuRegistryService;
        this.sifEventService = sifEventService;
        this.jdbc = jdbc;
        this.notificationService = notificationService;
    }

    /**
     * Notifica al asesor (si existe) que la cadena hash SIF de uno de
     * sus clientes está rota. Severity URGENT por ser tema legal —
     * inalterabilidad del registro AEAT.
     *
     * <p>Try/catch defensivo para no romper el scheduler si falla.
     */
    private void notifyAdvisorOfBrokenChain(String clientCompanyId, String target,
                                             String reason) {
        if (notificationService == null) return;
        try {
            // Buscar la asesoría del cliente (parent_company_id).
            String advisoryId = jdbc.query(
                    "SELECT parent_company_id FROM companies WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    clientCompanyId);
            if (advisoryId == null) return;
            String clientName = jdbc.query(
                    "SELECT legal_name FROM companies WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    clientCompanyId);
            if (clientName == null) clientName = clientCompanyId;
            String entityRef = "anomaly:" + clientCompanyId + ":" + target
                    + ":" + java.time.LocalDate.now();
            // Idempotencia diaria.
            Integer existing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM advisory_notifications
                     WHERE advisory_company_id = ? AND entity_ref = ?
                    """, Integer.class, advisoryId, entityRef);
            if (existing != null && existing > 0) return;
            notificationService.emit(
                    new com.benjagest.backend.advisory.notifications.AdvisoryNotificationService.EmitRequest(
                            advisoryId, clientCompanyId,
                            "SIF_CHAIN_BROKEN",
                            com.benjagest.backend.advisory.notifications.AdvisoryNotificationService.SEVERITY_URGENT,
                            "Cadena hash SIF rota en " + clientName,
                            "Anomalía detectada en cadena " + target
                                    + " del cliente " + clientName + ". Motivo: "
                                    + (reason == null ? "desconocido" : reason)
                                    + ". Esto puede indicar manipulación de los datos.",
                            entityRef));
        } catch (Exception ex) {
            log.warn("VF-ANOMALY: no se pudo notificar al asesor de {} ({})",
                    clientCompanyId, ex.getMessage());
        }
    }

    public void run() {
        log.info("VF-ANOMALY: arrancando ciclo de deteccion");
        detectInvoiceChainAnomalies();
        detectEventChainAnomalies();
        log.info("VF-ANOMALY: ciclo terminado");
    }

    private void detectInvoiceChainAnomalies() {
        List<VerifactuRegistryRepository.CompanyChainRef> refs;
        try {
            refs = registryRepository.findAllChainRefs();
        } catch (RuntimeException ex) {
            log.warn("VF-ANOMALY: no se pudo listar refs de cadenas", ex);
            return;
        }
        for (var ref : refs) {
            try {
                // Evento RUN antes de cada verificacion. Solo se
                // registra si la empresa esta en NO_VERIFACTU; en
                // VeriFactu el job tampoco rompe nada, simplemente el
                // evento no se persiste (recordForCompany filtra).
                String runPayload = "{\"target\":\"INVOICES\",\"mode\":\""
                        + ref.mode() + "\"}";
                sifEventService.recordForCompany(ref.companyId(),
                        "ANOMALY_DETECTION_INVOICES_RUN", runPayload);

                VerifactuRegistryService.IntegrityReport report =
                        verifactuRegistryService.verifyChainForCompany(ref.companyId(), ref.mode());

                if (!report.ok()) {
                    String hitPayload = "{\"mode\":\"" + ref.mode()
                            + "\",\"brokenInvoiceId\":\""
                            + nv(report.brokenInvoiceId()) + "\",\"brokenInvoiceNumber\":\""
                            + nv(report.brokenInvoiceNumber()) + "\",\"reason\":\""
                            + nv(report.reason()) + "\",\"checked\":"
                            + report.totalChecked() + "}";
                    sifEventService.recordForCompany(ref.companyId(),
                            "ANOMALY_DETECTION_INVOICES_HIT", hitPayload);
                    log.warn("VF-ANOMALY: cadena facturas rota en empresa {} mode {} ({})",
                            ref.companyId(), ref.mode(), report.reason());
                    // Notifica al asesor del cliente (si tiene): la
                    // cadena hash rota es tema legal — visibilidad
                    // prioritaria en bandeja con URGENT.
                    notifyAdvisorOfBrokenChain(ref.companyId(),
                            "facturas", report.reason());
                }
            } catch (RuntimeException ex) {
                log.warn("VF-ANOMALY: error verificando cadena facturas empresa={} mode={}",
                        ref.companyId(), ref.mode(), ex);
            }
        }
    }

    private void detectEventChainAnomalies() {
        // Las cadenas de eventos solo existen en NO_VERIFACTU, asi que
        // iteramos sobre esas empresas.
        List<String> companies = sifEventService.listCompaniesInNoVerifactu();
        for (String companyId : companies) {
            try {
                sifEventService.recordForCompany(companyId,
                        "ANOMALY_DETECTION_EVENTS_RUN", "{\"target\":\"EVENTS\"}");

                SifEventService.ChainVerifyResult report =
                        sifEventService.verifyChainForCompany(companyId);

                if (!report.ok()) {
                    String hitPayload = "{\"brokenEventId\":\""
                            + nv(report.brokenEventId()) + "\",\"brokenEventType\":\""
                            + nv(report.brokenEventType()) + "\",\"reason\":\""
                            + nv(report.reason()) + "\",\"checked\":"
                            + report.totalChecked() + "}";
                    sifEventService.recordForCompany(companyId,
                            "ANOMALY_DETECTION_EVENTS_HIT", hitPayload);
                    log.warn("VF-ANOMALY: cadena eventos SIF rota en empresa {} ({})",
                            companyId, report.reason());
                    notifyAdvisorOfBrokenChain(companyId, "eventos SIF", report.reason());
                }
            } catch (RuntimeException ex) {
                log.warn("VF-ANOMALY: error verificando cadena eventos empresa={}",
                        companyId, ex);
            }
        }
    }

    private String nv(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", " ");
    }
}
