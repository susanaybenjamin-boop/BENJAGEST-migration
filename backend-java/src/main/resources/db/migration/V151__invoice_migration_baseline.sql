-- MIG-1 — Punto de partida de facturación al migrar desde otro programa.
--
-- Guarda la ÚLTIMA factura emitida en el sistema anterior como base legal
-- para continuar la numeración sin huecos. Es solo REFERENCIA + PRUEBA:
-- NO se contabiliza (pertenece a la contabilidad del sistema anterior) y
-- NO genera cadena VeriFactu/SIF (la cadena SIF de BENJAGEST empieza limpia
-- en la primera factura emitida AQUÍ). La responsabilidad de que sea
-- realmente la última recae en el usuario vía declaración firmada.
CREATE TABLE invoice_migration_baseline (
    id                   CHAR(36)      NOT NULL,
    company_id           CHAR(36)      NOT NULL,
    series_id            CHAR(36)      NULL,
    declared_series_code VARCHAR(40)   NULL,
    declared_full_number VARCHAR(60)   NULL,
    declared_number      INT           NULL,
    declared_date        DATE          NULL,
    emitter_nif          VARCHAR(32)   NULL,
    customer_nif         VARCHAR(32)   NULL,
    customer_name        VARCHAR(180)  NULL,
    total_amount         DECIMAL(14,2) NULL,
    ocr_confidence       VARCHAR(20)   NULL,
    evidence_pdf_path    VARCHAR(500)  NULL,
    evidence_sha256      CHAR(64)      NULL,
    declaration_signed   TINYINT(1)    NOT NULL DEFAULT 0,
    declaration_text     TEXT          NULL,
    declared_by_user_id  CHAR(36)      NULL,
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_invoice_migration_baseline PRIMARY KEY (id),
    CONSTRAINT fk_imb_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_imb_series  FOREIGN KEY (series_id)  REFERENCES invoice_series (id)
)
-- IMPORTANTE: las tablas referenciadas (companies, invoice_series) usan
-- utf8mb4_unicode_ci, pero el DEFAULT de la BD en MariaDB 11.4 es
-- utf8mb4_uca1400_ai_ci. Sin fijar el COLLATE, las columnas FK no coinciden
-- con las referenciadas y MariaDB da errno 150 (FK mal formada).
DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_imb_company ON invoice_migration_baseline (company_id);
