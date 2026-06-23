-- ===========================================================================
-- V140 — Módulo Trabajos: tarifas por cliente (TRB-4).
--
-- Decisión Benjamin (2026-06-23): tabla de tarifas para autorrellenar el precio
-- al crear un trabajo. Cada fila = {cliente · unidad · concepto · precio}. Un
-- cliente puede tener VARIAS (p.ej. "Hora normal" 30€, "Hora urgente" 45€, "Día"
-- 200€, y servicios CERRADOS "Revisión" 150€). customer_id NULL = tarifa GENERAL
-- (por defecto, sirve cuando el cliente no tiene la suya).
--
-- Aditiva.
-- ===========================================================================

CREATE TABLE customer_work_rates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NULL,        -- NULL = tarifa general (por defecto)
    unit VARCHAR(10) NOT NULL,        -- HOURS | DAYS | MONTHS | FIXED
    concept VARCHAR(200) NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_customer_work_rates PRIMARY KEY (id),
    CONSTRAINT fk_cwr_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_cwr_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
    CONSTRAINT ck_cwr_unit CHECK (unit IN ('HOURS', 'DAYS', 'MONTHS', 'FIXED')),
    INDEX ix_cwr_company_customer (company_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
