package com.benjagest.backend.billing.sif;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.settings.CompanyDataRepository;
import com.benjagest.backend.settings.CompanyDataResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints del Registro de Eventos del SIF (slice VF-EVENTS-B).
 *
 *   GET  /api/billing/sif-events              listado filtrable por tipo.
 *   GET  /api/billing/sif-events/verify       integridad de la cadena.
 *   POST /api/billing/sif-events/export       genera evento EXPORT_EVENTS
 *                                             (el export real es VF3+;
 *                                              aqui solo registramos
 *                                              "se hizo una exportacion").
 *
 * No expone POST de creacion: los eventos se generan automaticamente
 * por hooks (lifecycle, validate, etc). El usuario solo lee y exporta.
 *
 * Solo OWNER/ADMIN/ACCOUNTANT: el registro de eventos es un documento
 * fiscal por extension; no debe verse en roles operativos.
 */
@RestController
@RequestMapping("/api/billing/sif-events")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class SifEventController {

    private final SifEventService eventService;
    private final SifEventRepository eventRepository;
    private final SifEventHashService hashService;
    private final CompanyDataRepository companyRepository;
    private final SifEventExportService exportService;

    public SifEventController(SifEventService eventService,
                              SifEventRepository eventRepository,
                              SifEventHashService hashService,
                              CompanyDataRepository companyRepository,
                              SifEventExportService exportService) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.hashService = hashService;
        this.companyRepository = companyRepository;
        this.exportService = exportService;
    }

    /**
     * Exporta el Registro de Eventos del SIF a PDF verificable. Cumple
     * el evento legal 9 (Orden HAC/1177/2024): "Exportación de
     * registros de eventos de un período". Emite EXPORT_EVENTS en la
     * cadena SIF + auditoría adicional con SHA-256 del documento.
     */
    @GetMapping(value = "/export.pdf", produces = "application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> exportPdf(
            @RequestParam("from") String fromIso,
            @RequestParam("to") String toIso,
            @RequestParam(value = "eventType", required = false) String eventType) {
        byte[] body = exportService.exportPdf(
                java.time.LocalDate.parse(fromIso),
                java.time.LocalDate.parse(toIso),
                eventType);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"sif-events-"
                        + fromIso + "_" + toIso + ".pdf\"")
                .body(body);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public org.springframework.http.ResponseEntity<byte[]> exportCsv(
            @RequestParam("from") String fromIso,
            @RequestParam("to") String toIso,
            @RequestParam(value = "eventType", required = false) String eventType) {
        byte[] body = exportService.exportCsv(
                java.time.LocalDate.parse(fromIso),
                java.time.LocalDate.parse(toIso),
                eventType);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"sif-events-"
                        + fromIso + "_" + toIso + ".csv\"")
                .body(body);
    }

    @GetMapping
    public List<SifEvent> list(@RequestParam(value = "eventType", required = false) String eventType,
                               @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return eventRepository.findForCompany(eventType, limit);
    }

    @GetMapping("/verify")
    public IntegrityReport verify() {
        CompanyDataResponse company = companyRepository.findCurrent().orElse(null);
        if (company == null || company.taxIdentifier() == null || company.taxIdentifier().isBlank()) {
            return new IntegrityReport(false, 0, null, null,
                    "La empresa no tiene NIF/CIF (companies.tax_identifier).");
        }
        List<SifEventRepository.ChainRow> chain = eventRepository.findChainOrderedAsc();
        String expectedPrev = "";
        int checked = 0;
        for (SifEventRepository.ChainRow row : chain) {
            checked++;
            OffsetDateTime gen = row.generatedAt().atZoneSameInstant(ZoneId.of("Europe/Madrid"))
                    .toOffsetDateTime();
            String recomputed = hashService.computeHash(
                    company.taxIdentifier(),
                    row.eventType(),
                    row.payload(),
                    expectedPrev,
                    gen
            );
            if (!recomputed.equalsIgnoreCase(row.hashCurrent())) {
                return new IntegrityReport(false, checked, row.id(), row.eventType(),
                        "Hash recalculado no coincide. Posible manipulación o desincronización de generation_time.");
            }
            if (!row.hashPrevious().equalsIgnoreCase(expectedPrev)) {
                return new IntegrityReport(false, checked, row.id(), row.eventType(),
                        "hash_previous almacenado no coincide con la cadena.");
            }
            expectedPrev = row.hashCurrent();
        }
        return new IntegrityReport(true, checked, null, null, null);
    }

    /**
     * Anyade un evento EXPORT_EVENTS a la cadena para dejar constancia
     * de que se hizo una exportacion del registro de eventos. El export
     * real (CSV/JSON con los eventos del periodo) se hara en otro slice
     * — aqui solo registramos el evento legal obligatorio (Orden HAC,
     * evento numero 9 de la lista).
     */
    @PostMapping("/export")
    public IntegrityReport recordExport(@RequestParam(value = "from", required = false) String from,
                                         @RequestParam(value = "to", required = false) String to) {
        String payload = "{\"from\":\"" + (from == null ? "" : from)
                + "\",\"to\":\"" + (to == null ? "" : to) + "\"}";
        eventService.record("EXPORT_EVENTS", payload);
        return verify();
    }

    public record IntegrityReport(
            boolean ok,
            int totalChecked,
            String brokenEventId,
            String brokenEventType,
            String reason
    ) {
    }
}
