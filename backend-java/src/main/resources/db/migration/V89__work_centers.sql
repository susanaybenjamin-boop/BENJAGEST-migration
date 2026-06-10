-- =============================================================================
-- V89 — Centros de trabajo (port CONTENDO centros_trabajo_180).
--
-- Decision Benjamin 2026-06-10: portar igual que CONTENDO, con
-- geolocalizacion opcional para validar fichajes (lat/lng/radio_m).
--
-- Cardinalidad empleado-centro: 1:N. Un empleado pertenece a 1 centro
-- en un momento dado (employees.work_center_id NULL hasta asignar).
-- =============================================================================

CREATE TABLE IF NOT EXISTS work_centers (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    name VARCHAR(180) NOT NULL,
    address VARCHAR(240) NULL,
    city VARCHAR(120) NULL,
    province VARCHAR(120) NULL,
    postal_code VARCHAR(20) NULL,
    -- Geolocalizacion opcional para validacion al fichar.
    lat DECIMAL(10,7) NULL,
    lng DECIMAL(10,7) NULL,
    radio_m INT NULL,
    -- none/info/soft/strict — cómo se reacciona si el fichaje cae
    -- fuera del radio. Por defecto 'info' (registra y avisa).
    geo_policy VARCHAR(10) NOT NULL DEFAULT 'info',
    notes TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_centers PRIMARY KEY (id),
    CONSTRAINT fk_work_centers_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_work_centers_geo_policy CHECK (geo_policy IN ('none','info','soft','strict')),
    INDEX ix_work_centers_company (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Asignacion empleado → centro de trabajo (nullable, 1:N).
ALTER TABLE employees
    ADD COLUMN work_center_id CHAR(36) NULL AFTER work_calendar_id,
    ADD CONSTRAINT fk_employees_work_center
        FOREIGN KEY (work_center_id) REFERENCES work_centers (id)
        ON DELETE SET NULL;
