-- ===========================================================================
-- V14__verifactu_registry.sql
--
-- Registro inmutable de cada factura validada bajo VeriFactu.
--
-- Modelo:
--   - 1 registro por (invoice_id, mode). La misma factura puede tener
--     un registro en TEST y otro en PROD si la empresa cambio de modo;
--     las dos cadenas son independientes.
--   - hash_current encadena con hash_previous = hash del registro
--     anterior de la misma (company_id, mode). El primero de la cadena
--     tiene hash_previous = '' (cadena vacia, segun especificacion AEAT).
--   - status describe el ciclo de vida del envio a AEAT:
--       PENDING       calculado, sin enviar aun.
--       SENT          enviado, esperando ack.
--       ACKNOWLEDGED  AEAT acepto.
--       ERROR         AEAT rechazo o fallo de conexion (retry_count > 0).
--   - last_error guarda el mensaje del ultimo intento fallido para que el
--     usuario pueda investigar sin abrir la BD.
--   - signature_data queda como TEXT para el slice VF2 (firma XAdES-EPES).
--
-- Reglas de integridad:
--   - UNIQUE(invoice_id, mode): garantiza que registrar dos veces la
--     misma factura en el mismo modo lanza error, defensa contra
--     duplicacion de hash.
--   - INDEX por (company_id, mode, generated_at): listado paginado por
--     cadena (es el orden cronologico de la cadena).
-- ===========================================================================

CREATE TABLE verifactu_registry (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    invoice_id CHAR(36) NOT NULL,
    mode VARCHAR(10) NOT NULL,
    hash_current VARCHAR(64) NOT NULL,
    hash_previous VARCHAR(64) NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP NULL,
    ack_at TIMESTAMP NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT NULL,
    signed_at TIMESTAMP NULL,
    signature_data TEXT NULL,
    CONSTRAINT pk_verifactu_registry PRIMARY KEY (id),
    CONSTRAINT uk_verifactu_registry_invoice_mode UNIQUE (invoice_id, mode),
    CONSTRAINT fk_verifactu_registry_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_verifactu_registry_invoice FOREIGN KEY (invoice_id) REFERENCES sales_invoices (id),
    CONSTRAINT ck_verifactu_registry_mode CHECK (mode IN ('TEST', 'PROD')),
    CONSTRAINT ck_verifactu_registry_status CHECK (status IN ('PENDING', 'SENT', 'ACKNOWLEDGED', 'ERROR')),
    INDEX ix_verifactu_registry_chain (company_id, mode, generated_at),
    INDEX ix_verifactu_registry_status (company_id, mode, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
