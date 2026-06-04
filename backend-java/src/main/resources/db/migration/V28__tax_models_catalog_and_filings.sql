-- ===========================================================================
-- V28__tax_models_catalog_and_filings.sql
--
-- Infraestructura para modelos AEAT (300+, 100, 130, 180, 190, 200,
-- 303, 347, 390, 411, 720…).
--
-- Decisiones:
--
--   - tax_model_catalog: catalogo global de modelos. Una sola fila por
--     codigo AEAT (303, 130, etc) con nombre, descripcion, periodicidad
--     (MONTHLY/QUARTERLY/ANNUAL) y URL informativa. Es global porque
--     los modelos son los mismos para todas las empresas.
--
--   - tax_filings: declaracion concreta de UNA empresa para UN modelo
--     en UN periodo. Estado DRAFT/READY/PRESENTED/PAID/REJECTED. Los
--     datos del modelo (casillas) van como JSON en `data` — cada modelo
--     tiene su estructura, no la materializamos en columnas distintas.
--     Cuando llegue el slice "modelo X completo" se programara su
--     generador especifico que sabe que casillas rellenar.
--
-- Empezamos sembrando los modelos mas comunes. La logica de generacion
-- de cada uno se cierra en sub-slices futuros (ver backlog).
-- ===========================================================================

CREATE TABLE tax_model_catalog (
    code VARCHAR(10) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description TEXT NULL,
    periodicity VARCHAR(20) NOT NULL,
    info_url VARCHAR(500) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_tax_model_catalog PRIMARY KEY (code),
    CONSTRAINT ck_tax_model_periodicity CHECK (periodicity IN ('MONTHLY', 'QUARTERLY', 'ANNUAL', 'BIANNUAL', 'AD_HOC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tax_model_catalog (code, name, description, periodicity, info_url) VALUES
('100', 'IRPF Declaracion anual de la Renta', 'Personas fisicas (autonomos, asalariados, mixtos).', 'ANNUAL', 'https://sede.agenciatributaria.gob.es/Sede/renta'),
('130', 'IRPF Pago fraccionado estimacion directa', 'Trimestral. Autonomos en estimacion directa.', 'QUARTERLY', NULL),
('131', 'IRPF Pago fraccionado estimacion objetiva', 'Trimestral. Autonomos en modulos.', 'QUARTERLY', NULL),
('180', 'Retenciones e ingresos a cuenta. Rendimientos de capital inmobiliario', 'Anual resumen de las retenciones del 115.', 'ANNUAL', NULL),
('190', 'Retenciones e ingresos a cuenta. Resumen anual', 'Anual. Resumen de las retenciones del 111 (trabajadores, profesionales).', 'ANNUAL', NULL),
('200', 'Impuesto sobre Sociedades', 'Anual. Sociedades.', 'ANNUAL', NULL),
('202', 'IS Pago fraccionado', 'Tres veces al ano (abr/oct/dic).', 'AD_HOC', NULL),
('303', 'IVA Autoliquidacion trimestral/mensual', 'El mas comun. IVA repercutido - soportado.', 'QUARTERLY', NULL),
('322', 'IVA Grupos. Modelo individual', 'Grupos de IVA.', 'MONTHLY', NULL),
('347', 'Operaciones con terceros >3005 EUR', 'Anual. Resumen de clientes/proveedores que superan el umbral.', 'ANNUAL', NULL),
('349', 'Operaciones intracomunitarias', 'Resumen de compras/ventas intracomunitarias.', 'MONTHLY', NULL),
('390', 'IVA Resumen anual', 'Anual. Resumen de todos los 303 del ano.', 'ANNUAL', NULL),
('411', 'Caja IVA. Resumen anual', 'Para entidades acogidas al regimen especial de criterio de caja.', 'ANNUAL', NULL),
('111', 'Retenciones IRPF trabajadores y profesionales', 'Trimestral. Lo que retienes a tus empleados/proveedores.', 'QUARTERLY', NULL),
('115', 'Retenciones IRPF alquileres', 'Trimestral. Lo que retienes al pagar alquileres a no exentos.', 'QUARTERLY', NULL),
('123', 'Retenciones rendimientos capital mobiliario', 'Trimestral.', 'QUARTERLY', NULL),
('720', 'Declaracion bienes en el extranjero', 'Anual. Bienes en el extranjero > 50.000 EUR.', 'ANNUAL', NULL);

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

-- ===========================================================================
-- Calendario fiscal: vencimientos calculados a partir de la
-- periodicidad. Vista — generamos las fechas en codigo Java cuando se
-- consulte, no las materializamos por ahora (los plazos cambian con
-- festivos y RD).
-- ===========================================================================
