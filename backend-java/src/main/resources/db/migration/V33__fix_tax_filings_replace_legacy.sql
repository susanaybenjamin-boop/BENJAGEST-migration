-- ===========================================================================
-- V33__fix_tax_filings_replace_legacy.sql
--
-- Bug detectado al arrancar: V2 creó tablas `tax_filings` y `tax_models`
-- como placeholders demo (esquema de Pablo de antes de la migración).
-- Mi V28 introdujo otro `tax_filings` con esquema real (tax_model_code,
-- period_quarter, period_month, total_amount, deadline_at...).
--
-- Como V28 usa `CREATE TABLE IF NOT EXISTS` (correcto para idempotencia),
-- en BBDD donde la legacy ya existía, V28 hizo skip y el endpoint
-- /api/tax/filings falla con "Unknown column 'tax_model_code'".
--
-- Plan:
--   - DROP TABLE IF EXISTS tax_filings (incluye constraints/data demo).
--   - CREATE TABLE tax_filings con el schema correcto.
--   - tax_models (legacy de V2) la dejamos: ya no choca — usamos
--     `tax_model_catalog` (V28) como catalogo real. La legacy queda
--     huérfana, sin uso desde código. Limpieza opcional futura.
-- ===========================================================================

DROP TABLE IF EXISTS tax_filings;

CREATE TABLE tax_filings (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    tax_model_code VARCHAR(10) NOT NULL,
    period_year INT NOT NULL,
    period_quarter INT NULL,
    period_month INT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    data JSON NULL,
    total_amount DECIMAL(14,2) NULL,
    deadline_at DATE NULL,
    presented_at TIMESTAMP NULL,
    presented_by CHAR(36) NULL,
    csv_aeat VARCHAR(120) NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_tax_filings PRIMARY KEY (id),
    CONSTRAINT uk_tax_filings_company_model_period UNIQUE (company_id, tax_model_code, period_year, period_quarter, period_month),
    CONSTRAINT fk_tax_filings_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_tax_filings_model FOREIGN KEY (tax_model_code) REFERENCES tax_model_catalog (code),
    CONSTRAINT fk_tax_filings_presenter FOREIGN KEY (presented_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_tax_filings_status CHECK (status IN ('DRAFT', 'READY', 'PRESENTED', 'PAID', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_tax_filings_quarter CHECK (period_quarter IS NULL OR period_quarter BETWEEN 1 AND 4),
    CONSTRAINT ck_tax_filings_month CHECK (period_month IS NULL OR period_month BETWEEN 1 AND 12),
    INDEX ix_tax_filings_company_status (company_id, status, deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
