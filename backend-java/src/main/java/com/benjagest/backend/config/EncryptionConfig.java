package com.benjagest.backend.config;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuracion del cifrado simetrico que protege datos sensibles
 * guardados en BD (password SMTP, keystores de certificados, tokens
 * Google, credenciales externas; RGPD 2026-07-08: tambien notas de
 * bajas medicas, motivo de ausencias e IBAN de empleados via
 * {@link FieldCipher}).
 *
 * RGPD (2026-07-08) — clave maestra POR INSTALACION:
 * BENJAGEST_ENCRYPTION_PASSWORD sigue mandando si el operador la puso;
 * en su defecto, {@link MasterKeyResolver} genera y protege un fichero
 * de clave en %ProgramData%\BENJAGEST\secret. La clave de desarrollo
 * queda solo como fallback de DESCIFRADO ({@link FallbackStringEncryptor})
 * para que lo cifrado por instalaciones previas siga siendo legible;
 * todo lo nuevo sale con la clave de la instalacion.
 *
 * Decision 7 de project-benjagest-architecture: cifrado de columnas en
 * aplicacion (Jasypt) en lugar de tablespace cifrado, para que el riesgo
 * de "alguien lee la BD" no exponga secretos.
 */
@Configuration
public class EncryptionConfig {

    @Bean
    public StringEncryptor stringEncryptor(@Value("${benjagest.encryption.password}") String password) {
        String resolved = MasterKeyResolver.resolve(password);
        StringEncryptor primary = buildEncryptor(resolved);
        if (MasterKeyResolver.DEV_SENTINEL.equals(resolved)) {
            // Sin fichero de clave posible y sin env var: comportamiento
            // previo tal cual (una sola clave, la de desarrollo).
            return primary;
        }
        return new FallbackStringEncryptor(primary,
                buildEncryptor(MasterKeyResolver.DEV_SENTINEL));
    }

    private StringEncryptor buildEncryptor(String password) {
        PooledPBEStringEncryptor encryptor = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig config = new SimpleStringPBEConfig();
        config.setPassword(password);
        config.setAlgorithm("PBEWithHmacSHA256AndAES_256");
        config.setKeyObtentionIterations("1000");
        config.setPoolSize("1");
        config.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
        config.setIvGeneratorClassName("org.jasypt.iv.RandomIvGenerator");
        config.setStringOutputType("base64");
        encryptor.setConfig(config);
        return encryptor;
    }
}
