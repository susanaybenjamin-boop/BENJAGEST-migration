-- ===========================================================================
-- V27__external_credentials_and_cert_log.sql
--
-- Dos tablas independientes pero del mismo bloque legal/funcional:
--
-- 1) external_credentials: credenciales cifradas para integraciones
--    externas (DEHu, SS RED, SILTRA, AEAT cl@ve...). Antes vivian en
--    .env o en post-it bajo el monitor; aqui van cifradas con Jasypt
--    y son por empresa.
--
-- 2) certificate_usage_log: cada vez que se usa un certificado .p12
--    para firmar/enviar algo, se anyade una linea. Trazabilidad
--    obligatoria por LOPD y para auditoria interna (si un .p12 firmara
--    algo no autorizado, hay registro).
-- ===========================================================================

CREATE TABLE external_credentials (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    system_code VARCHAR(40) NOT NULL,
    label VARCHAR(120) NOT NULL,
    username VARCHAR(180) NULL,
    encrypted_password TEXT NULL,
    auth_url VARCHAR(500) NULL,
    notes TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_external_credentials PRIMARY KEY (id),
    CONSTRAINT uk_external_credentials_company_system UNIQUE (company_id, system_code),
    CONSTRAINT fk_external_credentials_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_external_credentials_system CHECK (system_code IN (
        'DEHU', 'SS_RED', 'SILTRA', 'AEAT_CLAVE', 'NOTIFICA_GOB',
        'SEDE_AEAT', 'BANCO_ESPANA', 'OTHER'
    )),
    INDEX ix_external_credentials_company_active (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE certificate_usage_log (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    certificate_id CHAR(36) NOT NULL,
    user_id CHAR(36) NULL,
    used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purpose VARCHAR(80) NOT NULL,
    target_url VARCHAR(500) NULL,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    error_message TEXT NULL,
    ip_address VARCHAR(80) NULL,
    CONSTRAINT pk_certificate_usage_log PRIMARY KEY (id),
    CONSTRAINT fk_certificate_usage_log_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_certificate_usage_log_certificate FOREIGN KEY (certificate_id) REFERENCES digital_certificates (id),
    CONSTRAINT fk_certificate_usage_log_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    INDEX ix_certificate_usage_log_company_when (company_id, used_at DESC),
    INDEX ix_certificate_usage_log_cert (certificate_id, used_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
