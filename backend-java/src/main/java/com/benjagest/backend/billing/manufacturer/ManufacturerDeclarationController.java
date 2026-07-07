package com.benjagest.backend.billing.manufacturer;

import com.benjagest.backend.auth.RequiresRole;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone la declaracion responsable del fabricante del SIF al
 * usuario final. RD 1007/2023 + Orden HAC/1177/2024 art. 15.
 *
 * DR-1 (2026-07-07): la Orden exige que la declaracion conste "de modo
 * visible en el propio sistema informatico" — por eso NO lleva
 * {@code @RequiresModule("billing")}: debe verse aunque la empresa no
 * tenga el modulo de facturacion activo. Y todos los roles pueden
 * leerla (es informacion del producto, no datos de la empresa), por lo
 * que incluye EMPLOYEE como cualquier endpoint operacional.
 *
 * La version instalada del producto la conoce la UI
 * (UpdateService.APP_VERSION) y llega por query param.
 *
 * Endpoints:
 *   - GET /api/billing/manufacturer-declaration       → declaracion (JSON).
 *   - GET /api/billing/manufacturer-declaration/text  → texto plano (pantalla Acerca de).
 *   - GET /api/billing/manufacturer-declaration/pdf   → PDF descargable.
 */
@RestController
@RequestMapping("/api/billing/manufacturer-declaration")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class ManufacturerDeclarationController {

    private final ManufacturerDeclarationPdfService pdfService;

    public ManufacturerDeclarationController(ManufacturerDeclarationPdfService pdfService) {
        this.pdfService = pdfService;
    }

    @GetMapping
    public ManufacturerDeclaration get(
            @RequestParam(value = "version", required = false) String version) {
        return ManufacturerDeclaration.current(version);
    }

    @GetMapping(value = "/text", produces = "text/plain;charset=UTF-8")
    public String text(@RequestParam(value = "version", required = false) String version) {
        return pdfService.plainText(ManufacturerDeclaration.current(version));
    }

    @GetMapping(value = "/pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> pdf(
            @RequestParam(value = "version", required = false) String version) {
        ManufacturerDeclaration d = ManufacturerDeclaration.current(version);
        byte[] body = pdfService.pdf(d);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"declaracion-responsable-benjagest-"
                                + d.productVersion().replaceAll("[^0-9A-Za-z.\\-]", "") + ".pdf\"")
                .body(body);
    }
}
