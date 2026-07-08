package com.benjagest.backend.config;

import org.jasypt.encryption.StringEncryptor;

/**
 * RGPD (2026-07-08) — encryptor con ROTACION PEREZOSA de clave.
 *
 * Cifra SIEMPRE con la clave primaria (la de la instalacion). Al
 * descifrar, primero prueba la primaria; si falla, prueba la legacy
 * (la clave de desarrollo con la que las instalaciones previas
 * cifraron contrasenas SMTP, keystores de certificados y tokens de
 * Google). Asi el cambio de clave NO deja ilegible nada de lo ya
 * guardado, y cada re-guardado va saliendo con la clave nueva.
 */
public class FallbackStringEncryptor implements StringEncryptor {

    private final StringEncryptor primary;
    private final StringEncryptor legacy;

    public FallbackStringEncryptor(StringEncryptor primary, StringEncryptor legacy) {
        this.primary = primary;
        this.legacy = legacy;
    }

    @Override
    public String encrypt(String message) {
        return primary.encrypt(message);
    }

    @Override
    public String decrypt(String encryptedMessage) {
        try {
            return primary.decrypt(encryptedMessage);
        } catch (Exception primaryFailure) {
            // Valor cifrado con la clave anterior (instalacion previa a
            // la rotacion RGPD) — se lee con la legacy. NO se re-cifra
            // aqui (esto es una lectura); rotara al proximo guardado.
            return legacy.decrypt(encryptedMessage);
        }
    }
}
