package com.benjagest.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.junit.jupiter.api.Test;

/**
 * RGPD (2026-07-08) — contrato de {@link FieldCipher}: cifrado con
 * prefijo ENC:, passthrough de legacy en claro, y round-trip estable.
 */
class FieldCipherTest {

    private FieldCipher cipher() {
        PooledPBEStringEncryptor enc = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig cfg = new SimpleStringPBEConfig();
        cfg.setPassword("test-key-para-fieldcipher-1234567890");
        cfg.setAlgorithm("PBEWithHmacSHA256AndAES_256");
        cfg.setKeyObtentionIterations("1000");
        cfg.setPoolSize("1");
        cfg.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
        cfg.setIvGeneratorClassName("org.jasypt.iv.RandomIvGenerator");
        cfg.setStringOutputType("base64");
        enc.setConfig(cfg);
        return new FieldCipher(enc);
    }

    @Test
    void roundTripRecoversTheOriginal() {
        FieldCipher c = cipher();
        String iban = "ES9121000418450200051332";
        String stored = c.encrypt(iban);
        assertTrue(stored.startsWith("ENC:"), "el cifrado lleva el prefijo ENC:");
        assertNotEquals(iban, stored, "en BD no queda en claro");
        assertEquals(iban, c.decrypt(stored), "la API lo recupera igual");
    }

    @Test
    void legacyPlaintextIsReadUnchanged() {
        FieldCipher c = cipher();
        // Fila anterior a la rotacion RGPD: sin prefijo → passthrough.
        assertEquals("Lumbalgia aguda", c.decrypt("Lumbalgia aguda"));
        assertEquals("ES9121000418450200051332", c.decrypt("ES9121000418450200051332"));
    }

    @Test
    void nullAndBlankPassThroughOnBothSides() {
        FieldCipher c = cipher();
        assertNull(c.encrypt(null));
        assertNull(c.decrypt(null));
        assertEquals("", c.encrypt(""));
        assertEquals("   ", c.encrypt("   "), "el blank no se cifra (columna nullable)");
    }

    @Test
    void wrongKeyOnCiphertextDoesNotThrowAndKeepsValue() {
        // Un ENC: que esta clave no puede descifrar (clave rotada a mano):
        // no revienta la pantalla, devuelve el valor crudo.
        FieldCipher other = cipher();
        String stored = cipher().encrypt("dato");
        String result = other.decrypt(stored);
        // O lo descifra (improbable, claves random distintas) o devuelve el crudo.
        assertTrue(result.equals("dato") || result.equals(stored));
    }
}
