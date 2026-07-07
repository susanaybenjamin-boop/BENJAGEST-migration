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
 * DR-1 (2026-07-07): datos reales del productor (decision de Benjamin
 * en sesion 2026-07-07) y version del producto dinamica — la Orden
 * exige que la declaracion conste "en cada una de sus versiones", y la
 * version instalada la conoce la UI (UpdateService.APP_VERSION), que
 * la pasa como parametro al pedir la declaracion.
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

    /**
     * Declaracion oficial del producto BENJAGEST para la version
     * instalada. La version viene de la UI (unica fuente de verdad del
     * versionado del producto: se bumpea en cada release); si no llega,
     * se indica donde consultarla en lugar de inventar un numero.
     */
    public static ManufacturerDeclaration current(String productVersion) {
        String version = (productVersion == null || productVersion.isBlank())
                ? "(version instalada: ver Configuracion > Acerca de)"
                : productVersion.trim();
        return new ManufacturerDeclaration(
                "Benjamín Recio López",
                "74668351R",
                "susanaybenjamin@gmail.com",
                "España",
                "BENJAGEST",
                version,
                "Sistema Informático de Facturación (SIF) modular — facturación, "
                        + "contabilidad y gestión de asesoría, instalación local (on-premise)",
                "Facturación emitida con series de numeración; registro de facturación "
                        + "con huella (hash) SHA-256 encadenada en las modalidades «VERI*FACTU» y "
                        + "«NO VERI*FACTU»; registro de eventos del SIF con cadena propia; código QR "
                        + "de verificación y leyenda legal en la factura; anulación mediante factura "
                        + "rectificativa con vínculo a la original; proformas convertibles; firma "
                        + "electrónica XML-DSig de los registros (XAdES-EPES en hoja de ruta); "
                        + "detección periódica de anomalías en las cadenas (cada 12 h) y verificación "
                        + "de integridad bajo demanda; conservación sin purga automática; exportación "
                        + "de registros y eventos; generación de PDF y envío por correo al cliente.",
                "2026-07-07",
                "España",
                "El productor arriba identificado declara bajo su responsabilidad que este "
                        + "sistema informático de facturación cumple, en la modalidad de emisión "
                        + "«NO VERI*FACTU» (registro local de facturación con huella encadenada, "
                        + "registro de eventos, firma electrónica, conservación, integridad, "
                        + "trazabilidad e inalterabilidad de los registros), los requisitos del "
                        + "artículo 29.2.j) de la Ley 58/2003, General Tributaria, del Real Decreto "
                        + "1007/2023, de 5 de diciembre, y de la Orden HAC/1177/2024, de 17 de "
                        + "octubre. La modalidad de remisión voluntaria «VERI*FACTU» (envío de los "
                        + "registros de facturación a la sede electrónica de la AEAT) figura en la "
                        + "hoja de ruta del producto y no se ofrece operativa en esta versión."
        );
    }
}
