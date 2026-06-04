-- ===========================================================================
-- V32__dehu_notifications.sql
--
-- N1 — Bandeja DEHú.
--
-- DEHú = Direccion Electronica Habilitada unica (sede.administracion.gob.es).
-- Punto unico desde donde Hacienda, Seguridad Social, ayuntamientos,
-- DGT y demas organismos cuelgan las notificaciones electronicas
-- obligatorias para empresas.
--
-- Regla de oro: una notificacion DEHu se da por leida automaticamente
-- a los 10 dias naturales desde que se cuelga, abrieras o no. Por eso
-- es CRITICO tener un sistema interno que las inventarie en cuanto
-- llegan.
--
-- Modelo:
--
--   dehu_notifications: una fila por notificacion. Estado: PENDING
--     (no abierta), READ (abierta por el usuario), AUTO_READ (caducó
--     el plazo sin abrir), DISMISSED (descartada por el operador).
--
-- En esta version solo guardamos los metadatos y un puntero al
-- contenido (URL DEHu); el pull real desde el SOAP de DEHu queda
-- como integracion futura — usaremos external_credentials (V27) para
-- las credenciales del certificado de representante.
-- ===========================================================================

CREATE TABLE dehu_notifications (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    dehu_id VARCHAR(80) NOT NULL,
    nif_receiver VARCHAR(32) NOT NULL,
    organism_name VARCHAR(200) NOT NULL,
    organism_code VARCHAR(40) NULL,
    procedure_name VARCHAR(300) NULL,
    procedure_code VARCHAR(40) NULL,
    subject VARCHAR(400) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NULL,
    accessed_at TIMESTAMP NULL,
    read_at TIMESTAMP NULL,
    read_by CHAR(36) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    csv VARCHAR(120) NULL,
    content_url VARCHAR(500) NULL,
    local_pdf_path VARCHAR(500) NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_dehu_notifications PRIMARY KEY (id),
    CONSTRAINT uk_dehu_notifications_dehuid UNIQUE (company_id, dehu_id),
    CONSTRAINT fk_dehu_notifications_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_dehu_notifications_read_by FOREIGN KEY (read_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_dehu_notifications_status CHECK (status IN ('PENDING', 'READ', 'AUTO_READ', 'DISMISSED', 'EXPIRED')),
    INDEX ix_dehu_notifications_status (company_id, status, issued_at DESC),
    INDEX ix_dehu_notifications_expires (company_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
