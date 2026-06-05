package com.benjagest.backend.certificates;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Tests del parser de DN del KeystoreInspector contra ejemplos reales
 * de certificados FNMT y Camerfirma (sin cargar un .p12 — solo el
 * algoritmo de extracción de NIF y CN desde strings de DN típicas).
 *
 * Inspirado en la lógica de CONTENDO (certificadoService.js) pero
 * usando javax.naming.ldap.LdapName que parsea RFC2253 con escapes.
 */
class KeystoreInspectorTest {

    private final KeystoreInspector inspector = new KeystoreInspector();

    @Test
    void fnmt_personaFisica_extractsNifFromSerialNumber() throws Exception {
        // FNMT persona física: el NIF va en serialNumber con prefijo
        // "IDCES-". El CN repite el NIF al final separado por guion.
        String dn = "SERIALNUMBER=IDCES-74668351R, "
                + "GIVENNAME=BENJAMIN, "
                + "SURNAME=RECIO LOPEZ, "
                + "CN=RECIO LOPEZ\\, BENJAMIN - 74668351R, "
                + "C=ES";
        String nif = invokeGuessNif(
                "IDCES-74668351R",
                "RECIO LOPEZ, BENJAMIN - 74668351R",
                dn);
        assertEquals("74668351R", nif);
    }

    @Test
    void fnmt_representante_extractsCifFromSerialNumber() throws Exception {
        // FNMT representante: serialNumber con prefijo "VATES-" + CIF.
        String dn = "SERIALNUMBER=VATES-B12345678, "
                + "CN=NOMBRE EMPRESA SL - B12345678, "
                + "OU=REPRESENTANTE, "
                + "O=NOMBRE EMPRESA SL, C=ES";
        String nif = invokeGuessNif(
                "VATES-B12345678",
                "NOMBRE EMPRESA SL - B12345678",
                dn);
        assertEquals("B12345678", nif);
    }

    @Test
    void camerfirma_nifInCnOnly() throws Exception {
        // Algunos certificados no traen serialNumber; el NIF solo está
        // en el CN.
        String dn = "CN=JUAN GARCIA LOPEZ 12345678Z, O=CAMERFIRMA, C=ES";
        String nif = invokeGuessNif(null, "JUAN GARCIA LOPEZ 12345678Z", dn);
        assertEquals("12345678Z", nif);
    }

    @Test
    void prefixWithoutHyphen_isStripped() throws Exception {
        // Algunos certificados antiguos: "IDCES 12345678Z" sin guion.
        String nif = invokeGuessNif("IDCES 12345678Z", null, null);
        assertEquals("12345678Z", nif);
    }

    @Test
    void nifesPrefix_isStripped() throws Exception {
        String nif = invokeGuessNif("NIFES-87654321X", null, null);
        assertEquals("87654321X", nif);
    }

    /** Acceso a guessNif que es package-private (mismo paquete del test). */
    private String invokeGuessNif(String serial, String cn, String subjectDn) throws Exception {
        Method m = KeystoreInspector.class.getDeclaredMethod(
                "guessNif", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(inspector, serial, cn, subjectDn);
    }
}
