-- ===========================================================================
-- V9__company_email_config.sql
--
-- Configuracion SMTP por empresa. La pantalla "Configuracion -> Email"
-- de la UI lee y escribe esta tabla, y el endpoint /test-email la usa
-- para enviar un correo de prueba con los datos guardados.
--
-- Decisiones (project-benjagest-architecture, decision 7):
--   - La password del SMTP se guarda cifrada en aplicacion con Jasypt
--     (StringEncryptor). Nadie con acceso de SELECT a la BD ve el valor
--     real; hay que pasar por el backend, que tiene la master key en
--     variable de entorno BENJAGEST_ENCRYPTION_PASSWORD.
--   - 1 fila por empresa. PK = company_id, FK a companies.
--   - Toda la configuracion es opcional (la empresa puede no haber
--     configurado SMTP aun); por eso casi todo NULLABLE.
--
-- Notas:
--   - smtp_password_encrypted es texto largo porque el ciphertext de
--     Jasypt es BASE64 y puede ser largo. VARCHAR(512) deja margen.
--   - from_address es el "From" tal cual sale en los emails (puede ser
--     distinto del smtp_user, p.ej. user=facturas@ y from=admin@).
-- ===========================================================================

CREATE TABLE company_email_config (
    company_id CHAR(36) NOT NULL,
    smtp_host VARCHAR(180) NULL,
    smtp_port INT NULL,
    smtp_user VARCHAR(180) NULL,
    smtp_password_encrypted VARCHAR(512) NULL,
    from_address VARCHAR(180) NULL,
    from_name VARCHAR(180) NULL,
    reply_to VARCHAR(180) NULL,
    tls_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auth_required BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_company_email_config PRIMARY KEY (company_id),
    CONSTRAINT fk_company_email_config_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
