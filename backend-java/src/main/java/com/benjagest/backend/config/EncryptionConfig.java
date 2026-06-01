package com.benjagest.backend.config;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuracion del cifrado simetrico que protege datos sensibles
 * guardados en BD (password SMTP de la empresa, en el futuro tambien
 * keystores de certificados, credenciales DEHu, etc.).
 *
 * La master key sale de la variable de entorno BENJAGEST_ENCRYPTION_PASSWORD.
 * En desarrollo cae a un valor por defecto que NO debe usarse en produccion;
 * application.yml deja clara la advertencia.
 *
 * Decision 7 de project-benjagest-architecture: cifrado de columnas en
 * aplicacion (Jasypt) en lugar de tablespace cifrado, para que el riesgo
 * de "alguien lee la BD" no exponga secretos.
 */
@Configuration
public class EncryptionConfig {

    @Bean
    public StringEncryptor stringEncryptor(@Value("${benjagest.encryption.password}") String password) {
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
