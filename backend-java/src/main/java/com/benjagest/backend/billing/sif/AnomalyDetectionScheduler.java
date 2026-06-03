package com.benjagest.backend.billing.sif;

import com.benjagest.backend.billing.verifactu.VerifactuRegistryRepository;
import com.benjagest.backend.billing.verifactu.VerifactuRegistryService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de deteccion de anomalias en las cadenas hash — slice VF-ANOMALY.
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
 * Frecuencia: cada 12 horas. La Orden no especifica un periodo concreto;
 * 12h es defensivo (dos pasadas por dia) sin saturar la BD. Cuando una
 * empresa tenga miles de facturas, la verificacion sera lineal (verify
 * recorre toda la cadena) — si el coste se nota, se pasa a diario.
 *
 * Iteramos por (companyId, mode) usando findAllChainRefs, asi cada
 * cadena se verifica una vez. Para los eventos SIF la cadena es
 * unica por empresa (sin mode), asi que iteramos las empresas
 * NO_VERIFACTU una sola vez por ciclo.
 */
@Component
public class AnomalyDetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionScheduler.class);
    private static final long TWELVE_HOURS_MS = 12L * 60L * 60L * 1000L;
    private static final long INITIAL_DELAY_MS = 15L * 60L * 1000L;

    private final VerifactuRegistryRepository registryRepository;
    private final VerifactuRegistryService verifactuRegistryService;
    private final SifEventService sifEventService;

    public AnomalyDetectionScheduler(VerifactuRegistryRepository registryRepository,
                                      VerifactuRegistryService verifactuRegistryService,
                                      SifEventService sifEventService) {
        this.registryRepository = registryRepository;
        this.verifactuRegistryService = verifactuRegistryService;
        this.sifEventService = sifEventService;
    }

    @Scheduled(fixedDelay = TWELVE_HOURS_MS, initialDelay = INITIAL_DELAY_MS)
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
