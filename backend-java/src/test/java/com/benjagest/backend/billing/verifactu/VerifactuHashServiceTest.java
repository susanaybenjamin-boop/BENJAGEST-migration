package com.benjagest.backend.billing.verifactu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * RECT (2026-07-08) — El TipoFactura entro en el canonical de la huella
 * VeriFactu. Estos tests fijan el contrato de compatibilidad: las
 * cadenas historicas (registros con invoice_type_code NULL) tienen que
 * recalcular EXACTAMENTE el mismo hash que antes del bloque RECT, o la
 * deteccion de anomalias daria falso "cadena rota" en datos legitimos
 * (mismo tipo de bug que el VF-CHAIN-FIX de timezone de 2026-06-08).
 */
class VerifactuHashServiceTest {

    private final VerifactuHashService service = new VerifactuHashService();

    private static final OffsetDateTime GEN =
            OffsetDateTime.of(2026, 7, 8, 10, 30, 0, 0, ZoneOffset.ofHours(2));

    private String hash(String typeCode) {
        return service.computeHash("74668351R", "FRA-2026-0001",
                LocalDate.of(2026, 7, 8), new BigDecimal("21.00"),
                new BigDecimal("121.00"), "", GEN, null, null, typeCode);
    }

    @Test
    void nullTypeRecomputesExactlyTheHistoricalHash() {
        // La sobrecarga antigua (sin tipo) y la nueva con tipo null/blank/F1
        // producen el MISMO hash: los registros historicos siguen verificables.
        String legacy = service.computeHash("74668351R", "FRA-2026-0001",
                LocalDate.of(2026, 7, 8), new BigDecimal("21.00"),
                new BigDecimal("121.00"), "", GEN);
        assertEquals(legacy, hash(null));
        assertEquals(legacy, hash(""));
        assertEquals(legacy, hash("F1"));
        assertEquals(legacy, hash("f1"), "el tipo se normaliza a mayusculas");
    }

    @Test
    void eachInvoiceTypeProducesADistinctChainedHash() {
        String f1 = hash("F1");
        for (String code : new String[]{"F2", "R1", "R2", "R3", "R4", "R5"}) {
            assertNotEquals(f1, hash(code), "el tipo " + code + " debe alterar la huella");
        }
        assertNotEquals(hash("R1"), hash("R4"), "causas distintas, huellas distintas");
    }

    @Test
    void legacyTpbOverloadStillMatchesNewSignatureWithNullType() {
        String tpbLegacy = service.computeHash("74668351R", "FRA-2026-0001",
                LocalDate.of(2026, 7, 8), new BigDecimal("21.00"),
                new BigDecimal("121.00"), "", GEN, "B09990001", "Asesoria Demo SL");
        String tpbNew = service.computeHash("74668351R", "FRA-2026-0001",
                LocalDate.of(2026, 7, 8), new BigDecimal("21.00"),
                new BigDecimal("121.00"), "", GEN, "B09990001", "Asesoria Demo SL", null);
        assertEquals(tpbLegacy, tpbNew);
    }
}
