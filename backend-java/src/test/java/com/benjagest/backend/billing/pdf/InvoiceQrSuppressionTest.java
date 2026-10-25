package com.benjagest.backend.billing.pdf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.benjagest.backend.billing.verifactu.VerifactuConfig;
import org.junit.jupiter.api.Test;

/**
 * VF-QR-NODATE (2026-10-25, decisión de Benjamin): el interruptor del QR
 * VERI*FACTU ya no caduca. Antes, desde la fecha de obligación deducida del
 * NIF (1-ene-2027 sociedades, 1-jul-2027 autónomos) el backend ignoraba el
 * "apagado" y volvía a imprimir el QR. Ahora el QR sale solo si está marcado.
 */
class InvoiceQrSuppressionTest {

    private static VerifactuConfig config(Boolean printQr) {
        return new VerifactuConfig("VERIFACTU", "TEST", null, null, null, null, printQr, null);
    }

    private final InvoiceQrService service = new InvoiceQrService();

    @Test
    void apagadoSeRespetaSiempre() {
        assertTrue(service.qrSuppressed(config(false), null));
    }

    @Test
    void encendidoImprimeElQr() {
        assertFalse(service.qrSuppressed(config(true), null));
    }

    @Test
    void sinConfigOSinValorSeImprimePorDefecto() {
        assertFalse(service.qrSuppressed(null, null));
        assertFalse(service.qrSuppressed(config(null), null));
    }
}
