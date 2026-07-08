package com.benjagest.backend.purchases;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** OPTYPE-3 — auto-detección del tipo de operación por el NIF del proveedor. */
class OperationTypeDetectorTest {

    @Test
    void nifEspanol_CIF_esInterior() {
        assertEquals("INTERIOR", OperationTypeDetector.detect("B12345678"));
    }

    @Test
    void nifEspanol_NIE_esInterior() {
        assertEquals("INTERIOR", OperationTypeDetector.detect("X1234567L"));
    }

    @Test
    void nifEspanol_DNI_esInterior() {
        assertEquals("INTERIOR", OperationTypeDetector.detect("12345678Z"));
    }

    @Test
    void nifFrances_esIntracom() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("FR12345678901"));
    }

    @Test
    void nifAleman_esIntracom() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("DE123456789"));
    }

    @Test
    void nifPortugues_esIntracom() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("PT123456789"));
    }

    @Test
    void grecia_usaEL_esIntracom() {
        assertEquals("INTRACOM", OperationTypeDetector.detect("EL123456789"));
    }

    @Test
    void prefijoES_deVIES_esInterior() {
        // Un NIF español en formato VIES (ESB12345678) NO es intracom.
        assertEquals("INTERIOR", OperationTypeDetector.detect("ESB12345678"));
    }

    @Test
    void toleraEspaciosGuionesYMinusculas() {
        assertEquals("INTRACOM", OperationTypeDetector.detect(" fr-123 456 789 "));
    }

    @Test
    void nuloOvacio_esInterior() {
        assertEquals("INTERIOR", OperationTypeDetector.detect(null));
        assertEquals("INTERIOR", OperationTypeDetector.detect("  "));
    }
}
