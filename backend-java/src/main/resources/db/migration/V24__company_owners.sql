-- ===========================================================================
-- V24__company_owners.sql
--
-- Titulares de empresa: administradores, socios, autonomos asociados.
--
-- Imprescindible para:
--   - Modelo 200 (impuesto sociedades): exige listado de
--     administradores con sus % participacion y fechas de cargo.
--   - Modelo 184 (entidades en regimen de atribucion): % de los
--     socios.
--   - Informes Seguridad Social: regimen de cotizacion de cada
--     titular (RETA, autonomo societario, asalariado, etc).
--
-- Decisiones:
--   - Una persona fisica/juridica puede ser titular de varias empresas
--     (un mismo administrador en S.L. + S.L.U.). Por eso tax_identifier
--     NO es UNIQUE — la pareja (company_id, tax_identifier) si.
--   - role describe el cargo legal: ADMINISTRATOR (administrador
--     unico), JOINT_ADMINISTRATOR (mancomunado), SOLE_ADMINISTRATOR
--     (en solitario), BOARD_MEMBER (consejero), PARTNER (socio sin
--     cargo administrativo), AUTONOMOUS (autonomo del negocio).
--   - ss_regime: codigo del regimen SS — RETA (autonomos),
--     GENERAL (regimen general asalariados), AUTONOMO_SOCIETARIO,
--     NO_COTIZA (consejeros sin nomina), OTHER.
--   - ownership_percent: 0..100 con 2 decimales. Sum debe ser <=100
--     pero NO lo forzamos en BD (los casos reales tienen complejidades:
--     acciones propias, autocartera, etc); validacion en service.
-- ===========================================================================

CREATE TABLE company_owners (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    full_name VARCHAR(180) NOT NULL,
    tax_identifier VARCHAR(32) NOT NULL,
    role VARCHAR(40) NOT NULL,
    ownership_percent DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    ss_regime VARCHAR(40) NULL,
    appointment_date DATE NULL,
    termination_date DATE NULL,
    email VARCHAR(180) NULL,
    phone VARCHAR(40) NULL,
    notes TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_company_owners PRIMARY KEY (id),
    CONSTRAINT uk_company_owners_company_tax UNIQUE (company_id, tax_identifier),
    CONSTRAINT fk_company_owners_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_company_owners_role CHECK (role IN (
        'ADMINISTRATOR', 'JOINT_ADMINISTRATOR', 'SOLE_ADMINISTRATOR',
        'BOARD_MEMBER', 'PARTNER', 'AUTONOMOUS'
    )),
    CONSTRAINT ck_company_owners_ss_regime CHECK (ss_regime IS NULL OR ss_regime IN (
        'RETA', 'GENERAL', 'AUTONOMO_SOCIETARIO', 'NO_COTIZA', 'OTHER'
    )),
    CONSTRAINT ck_company_owners_percent CHECK (ownership_percent >= 0 AND ownership_percent <= 100),
    INDEX ix_company_owners_company_active (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
