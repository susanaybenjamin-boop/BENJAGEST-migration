package com.benjagest.backend.purchases;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** OPTYPE-3 — auto-detección del tipo de operación por el NIF y el IVA. */
class OperationTypeDetectorTest {

    private static final BigDecimal SIN_IVA = BigDecimal.ZERO;
    private static BigDecimal iva(String s) { return new BigDecimal(s); }

    @Test
    void nifEspanol_CIF_esInterior() {
        assertEquals("INTERIOR", OperationTypeDetector.detect("B12345678", SIN_IVA));
    }

    @Test
    void nifEspanol_NIE_esInterior() {
        assertEquals("INTERIOR", OperationTypeDetector.detect("X1234567L", SIN_IVA));
    }

    @Test
    void nifEspanol_DNI_esInterior() {
        assertEquals("INTERIOR", OperationTypeDetector.detect("12345678Z", SIN_IVA));
    }

    @Test
    void nifFrances_sinIva_esIntracom() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("FR12345678901", SIN_IVA));
    }

    @Test
    void nifAleman_sinIva_esIntracom() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("DE123456789", SIN_IVA));
    }

    @Test
    void nifPortugues_sinIva_esIntracom() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("PT123456789", SIN_IVA));
    }

    @Test
    void grecia_usaEL_esIntracom() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("EL123456789", SIN_IVA));
    }

    @Test
    void prefijoES_deVIES_esInterior() {
        // Un NIF español en formato VIES (ESB12345678) NO es intracom.
        assertEquals("INTERIOR", OperationTypeDetector.detect("ESB12345678", SIN_IVA));
    }

    @Test
    void toleraEspaciosGuionesYMinusculas() {
        assertEquals("INTRACOM", OperationTypeDetector.detect(" fr-123 456 789 ", SIN_IVA));
    }

    @Test
    void nuloOvacio_esInterior() {
        assertEquals("INTERIOR", OperationTypeDetector.detect(null, SIN_IVA));
        assertEquals("INTERIOR", OperationTypeDetector.detect("  ", SIN_IVA));
    }

    // ---- Casos REALES de los PDFs de Benjamin (T2 2026, Amazon) ----

    @Test
    void amazonEU_nifLuxemburgo_conIvaEspanol_esInterior() {
        // Milwaukee cinta métrica: NIF LU20260743 pero repercute IVA 21% ES.
        // Es interior (ventanilla única), NO intracom.
        assertEquals("INTERIOR", OperationTypeDetector.detect("LU20260743", iva("4.56")));
    }

    @Test
    void amazonMarketplace_vendedorChino_conIvaEspanol_esInterior() {
        // Repuesto acelerador: vendido por empresa china (CN), pero Amazon
        // declara el IVA español (21%). Interior deducible, entra en 130 y 303.
        assertEquals("INTERIOR", OperationTypeDetector.detect("LU20260743", iva("1.47")));
        assertEquals("INTERIOR", OperationTypeDetector.detect("CN", iva("1.47")));
    }

    @Test
    void proveedorUE_conIvaCero_siEsIntracom() {
        // Adquisición intracom B2B real: proveedor alemán SIN IVA (inversión
        // del sujeto pasivo) -> INTRACOM.
        assertEquals("INTRACOM", OperationTypeDetector.detect("DE811234567", iva("0")));
    }

    @Test
    void ivaNulo_seTrataComoSinIva() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("FR12345678901", null));
    }
}
