package com.benjagest.backend.billing.email;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * MAIL-CC — "Enviar a otro email" y "Con copia a" admiten varias direcciones
 * separadas por coma o punto y coma. Una dirección mal escrita debe dar un 400
 * que diga CUÁL es, no el error críptico del servidor de correo.
 */
class InvoiceEmailAddressesTest {

    @Test
    void vacioNoEsError() {
        assertEquals(0, InvoiceEmailService.parseAddresses(null, "cc").length);
        assertEquals(0, InvoiceEmailService.parseAddresses("   ", "cc").length);
    }

    @Test
    void variasDireccionesConComaOPuntoYComa() {
        assertArrayEquals(new String[]{"a@x.es", "b@y.com", "c@z.org"},
                InvoiceEmailService.parseAddresses(" a@x.es, b@y.com ;c@z.org ;", "cc"));
    }

    @Test
    void repetidasSeQuitan() {
        assertArrayEquals(new String[]{"a@x.es"},
                InvoiceEmailService.parseAddresses("a@x.es, a@x.es", "cc"));
    }

    @Test
    void direccionMalEscritaDa400ConLaCulpable() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> InvoiceEmailService.parseAddresses("a@x.es, pepe.gmail.com", "con copia"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("pepe.gmail.com"));
        assertTrue(ex.getReason().contains("con copia"));
    }

    @Test
    void espacioDentroDeLaDireccionNoVale() {
        assertThrows(ResponseStatusException.class,
                () -> InvoiceEmailService.parseAddresses("ana lopez@x.es", "destinatario"));
    }
}
