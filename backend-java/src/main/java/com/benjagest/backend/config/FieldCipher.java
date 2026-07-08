package com.benjagest.backend.config;

import org.jasypt.encryption.StringEncryptor;
import org.springframework.stereotype.Component;

/**
 * RGPD (2026-07-08) — cifrado de CAMPOS con datos personales sensibles
 * que ya existian en claro (notas de bajas medicas, motivo de ausencias,
 * IBAN de empleados).
 *
 * Convencion de almacenamiento: los valores cifrados llevan el prefijo
 * {@code ENC:} delante del base64 de Jasypt. Asi:
 *
 * <ul>
 *   <li>Los datos LEGACY en claro se siguen leyendo tal cual (sin
 *       prefijo → passthrough), sin necesitar una migracion big-bang
 *       que podria fallar a medias.</li>
 *   <li>Cada escritura (alta o edicion) sale cifrada — rotacion
 *       perezosa: el parque en claro se va cifrando con el uso.</li>
 *   <li>Un valor cifrado nunca se confunde con uno en claro (nadie
 *       escribe notas que empiecen por "ENC:"; si pasara, el decrypt
 *       fallido cae a passthrough y no se pierde nada).</li>
 * </ul>
 *
 * NO usar para columnas que participen en agregaciones o filtros SQL
 * (importes de nomina: SUM en cierre fiscal y reportes) — esas se
 * protegen con control de acceso + auditoria de lecturas, no con
 * cifrado de columna.
 */
@Component
public class FieldCipher {

    private static final String PREFIX = "ENC:";

    private final StringEncryptor encryptor;

    public FieldCipher(StringEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    /** Cifra para guardar. null/blank pasan tal cual (columna nullable). */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return plain;
        return PREFIX + encryptor.encrypt(plain);
    }

    /**
     * Descifra al leer. Sin prefijo = valor legacy en claro → se
     * devuelve tal cual. Si el descifrado falla (clave rotada a mano,
     * corrupcion), se devuelve el valor crudo antes que romper la
     * pantalla — el dato sigue siendo del usuario.
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            return encryptor.decrypt(stored.substring(PREFIX.length()));
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(FieldCipher.class)
                    .warn("FieldCipher: no se pudo descifrar un campo (¿clave cambiada?): {}",
                            ex.getMessage());
            return stored;
        }
    }
}
