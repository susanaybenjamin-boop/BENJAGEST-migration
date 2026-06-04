package com.benjagest.backend.billing.manufacturer;

/**
 * Declaracion responsable del fabricante del Sistema Informatico de
 * Facturacion (SIF) — RD 1007/2023 + Orden HAC/1177/2024 art. 15.
 *
 * El RD exige que cada SIF incorpore una "declaracion responsable"
 * suscrita por su productor/fabricante/desarrollador con:
 *
 * <ul>
 *   <li>Datos identificativos y de localizacion del productor.</li>
 *   <li>Datos del sistema (nombre, version, tipo, composicion, funcionalidades).</li>
 *   <li>Fecha y lugar de la declaracion.</li>
 *   <li>Compromiso de cumplir los requisitos del RD 1007/2023.</li>
 * </ul>
 *
 * Es un MODELO DE AUTOCERTIFICACION — no requiere intervencion de
 * tercero. La responsabilidad recae integramente en el fabricante.
 *
 * Aqui los datos son los del proyecto BENJAGEST. Cuando el proyecto
 * tenga forma juridica (autonomo, S.L., etc), se actualizan estos
 * valores y la fecha de la declaracion.
 */
public record ManufacturerDeclaration(
        // Identificacion del fabricante
        String manufacturerName,
        String manufacturerTaxIdentifier,
        String manufacturerEmail,
        String manufacturerAddress,
        // Identificacion del producto
        String productName,
        String productVersion,
        String productType,
        String productFunctionalities,
        // Fecha y lugar de la declaracion
        String declarationDate,
        String declarationPlace,
        // Compromiso explicito (RD 1007/2023)
        String complianceCommitment
) {

    /** Declaracion oficial del producto BENJAGEST. */
    public static ManufacturerDeclaration current() {
        return new ManufacturerDeclaration(
                "BENJAGEST (proyecto personal — pendiente forma juridica)",
                "PENDIENTE",
                "susanaybenjamin@gmail.com",
                "Espana",
                "BENJAGEST",
                "0.1.0-SNAPSHOT",
                "Sistema Informatico de Facturacion (SIF) modular — facturacion + gestoria",
                "Facturacion emitida, series de numeracion, hash encadenado VeriFactu/NO VeriFactu, "
                        + "registro de eventos del SIF, firma XML-DSig (XAdES-EPES en hoja de ruta), "
                        + "envio a AEAT VeriFactu (en hoja de ruta), almacenamiento documental, "
                        + "envio email cliente, anulacion por rectificativa con vinculo, "
                        + "proformas convertibles, deteccion de anomalias en cadenas, "
                        + "verificacion de integridad bajo demanda y periodica (12h).",
                "2026-06-04",
                "Espana (online)",
                "El fabricante declara bajo su responsabilidad que este SIF cumple los requisitos "
                        + "establecidos por el Real Decreto 1007/2023, de 5 de diciembre, y la Orden "
                        + "HAC/1177/2024, de 17 de octubre, en su mejor entendimiento y a su mejor "
                        + "esfuerzo, con las puntualizaciones documentadas en docs/backlog.md "
                        + "(VF-SIGN-XADES-AEAT, VF3-SOAP afinado, VF-EVENTS export real, OCR "
                        + "importacion). El cumplimiento estricto del envio a AEAT VeriFactu requiere "
                        + "completar el slice VF-SIGN-XADES-AEAT y la prueba contra entorno AEAT real "
                        + "con certificado FNMT representante."
        );
    }
}
