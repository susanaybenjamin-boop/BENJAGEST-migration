-- ===========================================================================
-- V30__reta_profiles_and_base_changes.sql
--
-- L2 — RETA (Regimen Especial de Trabajadores Autonomos).
--
-- Modelo de datos:
--
--   reta_profiles: perfil de cotizacion del autonomo. Una fila por
--     titular (o por la empresa misma si es autonomo persona fisica).
--     Si la empresa es SL, los administradores autonomos societarios
--     tambien viven aqui — un perfil por cada uno.
--
--   reta_base_changes: cada cambio de base de cotizacion declarado
--     a la SS. Hasta 6 cambios al ano. Tipico: ajustar base segun
--     rendimientos netos previstos (sistema nuevo desde 2023, RD-Ley
--     13/2022).
--
-- Decisiones:
--
--   - No materializamos los tramos de cuota — son normativos y cambian
--     cada ano. Se calcula en codigo segun ano + rendimientos netos.
--   - El perfil enlaza opcionalmente con company_owners (titular) o con
--     employees (empleado autonomo societario). Solo uno de los dos.
-- ===========================================================================

CREATE TABLE reta_profiles (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NULL,
    employee_id CHAR(36) NULL,
    full_name VARCHAR(160) NOT NULL,
    tax_identifier VARCHAR(32) NULL,
    social_security_number VARCHAR(20) NULL,
    reta_start_date DATE NULL,
    reta_end_date DATE NULL,
    pluriactividad BOOLEAN NOT NULL DEFAULT FALSE,
    tarifa_plana BOOLEAN NOT NULL DEFAULT FALSE,
    tarifa_plana_until DATE NULL,
    activity_code VARCHAR(20) NULL,
    activity_description VARCHAR(200) NULL,
    iae_epigraph VARCHAR(20) NULL,
    expected_net_income DECIMAL(14,2) NULL,
    current_base DECIMAL(14,2) NULL,
    current_quota DECIMAL(14,2) NULL,
    notes TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_reta_profiles PRIMARY KEY (id),
    CONSTRAINT uk_reta_profiles_owner UNIQUE (company_id, owner_id),
    CONSTRAINT uk_reta_profiles_employee UNIQUE (company_id, employee_id),
    CONSTRAINT fk_reta_profiles_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_reta_profiles_owner FOREIGN KEY (owner_id) REFERENCES company_owners (id),
    CONSTRAINT fk_reta_profiles_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT ck_reta_profiles_exclusive CHECK (
        (owner_id IS NULL AND employee_id IS NULL)
        OR (owner_id IS NOT NULL AND employee_id IS NULL)
        OR (owner_id IS NULL AND employee_id IS NOT NULL)
    ),
    INDEX ix_reta_profiles_company (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reta_base_changes (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    profile_id CHAR(36) NOT NULL,
    effective_date DATE NOT NULL,
    change_reason VARCHAR(80) NULL,
    new_base DECIMAL(14,2) NOT NULL,
    new_quota DECIMAL(14,2) NULL,
    expected_net_income DECIMAL(14,2) NULL,
    submitted_to_ss BOOLEAN NOT NULL DEFAULT FALSE,
    submitted_at TIMESTAMP NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_reta_base_changes PRIMARY KEY (id),
    CONSTRAINT fk_reta_base_changes_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_reta_base_changes_profile FOREIGN KEY (profile_id) REFERENCES reta_profiles (id),
    INDEX ix_reta_base_changes_profile_date (profile_id, effective_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
