package com.benjagest.backend.labor.contracts;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CTR-4 + CTR-5 — Endpoints de descarga de documentos del contrato.
 *
 * <ul>
 *   <li>{@code GET /api/contracts/{id}/pdf?model=UNIFIED_2022|BY_CODE}
 *       — devuelve el PDF firmable.</li>
 *   <li>{@code GET /api/contracts/{id}/xml}
 *       — devuelve el XML para Contrat@ (SEPE).</li>
 * </ul>
 *
 * Permitido a ACCOUNTANT/ADMIN/OWNER. La UI puede ofrecer el botón
 * "Generar PDF / XML" desde el listado del módulo Laboral → Contratos.
 */
@RestController
@RequestMapping("/api/contracts")
@RequiresModule("labor")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "ADVISOR"})
public class ContractDocumentController {

    private final ContractPdfGenerator pdfGen;
    private final ContractXmlGenerator xmlGen;

    public ContractDocumentController(ContractPdfGenerator pdfGen, ContractXmlGenerator xmlGen) {
        this.pdfGen = pdfGen;
        this.xmlGen = xmlGen;
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable("id") String id,
            @RequestParam(value = "model", required = false) String model) {
        byte[] pdf = pdfGen.generate(id, model);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"contrato-" + id.substring(0, 8) + ".pdf\"")
                .body(pdf);
    }

    @GetMapping("/{id}/xml")
    public ResponseEntity<byte[]> downloadXml(@PathVariable("id") String id,
            @org.springframework.web.bind.annotation.RequestParam(value = "signed", required = false)
            Boolean signed) {
        // CTR-XADES (v2): si signed=true, firmar el XML con el cert .p12
        // de la asesoría (CERT-IMPORT) usando XAdES-EPES. v1 no lo
        // implementa todavía — devolvemos 501 NOT_IMPLEMENTED con un
        // mensaje claro para que la UI pueda explicarlo. Ver el javadoc
        // de ContractXmlGenerator para el plan completo.
        if (signed != null && signed) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_IMPLEMENTED,
                    "Firma XAdES del XML Contrat@ no implementada todavía. "
                            + "Disponible en v2 — por ahora descarga el XML sin firma "
                            + "y firma manualmente con AutoFirma si el SEPE lo requiere.");
        }
        byte[] xml = xmlGen.generate(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"contrat-" + id.substring(0, 8) + ".xml\"")
                .body(xml);
    }
}
