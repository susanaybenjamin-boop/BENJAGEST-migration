-- ===========================================================================
-- V5__reta_extension_tables.sql
-- Completa el modelo RETA con las dos tablas que faltaban segun
-- docs/gap-analysis-contendo.md (apartado 3.G):
--   1) self_employed_base_changes      -> trazabilidad de cambios de base
--                                         de cotizacion del autonomo.
--   2) self_employed_preonboarding     -> flujo de captacion del prospecto
--                                         antes de darlo de alta como cliente.
-- Migracion puramente aditiva: no toca tablas ni columnas existentes.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) Cambios de base de cotizacion
--    La SS permite al autonomo cambiar de tramo varias veces al ano. Hay que
--    guardar el historico: tramo/base anterior y nuevo, fechas (solicitud,
--    limite, efectiva), estado del tramite, justificante PDF y trazabilidad
--    de quien lo solicita y quien lo confirma.
-- ---------------------------------------------------------------------------
CREATE TABLE self_employed_base_changes (
    id CHAR(36) NOT NULL,
    profile_id CHAR(36) NOT NULL,
    year_number INT NOT NULL,
    previous_base DECIMAL(14,2) NOT NULL,
    new_base DECIMAL(14,2) NOT NULL,
    previous_bracket INT NULL,
    new_bracket INT NULL,
    request_date DATE NOT NULL,
    request_deadline_date DATE NOT NULL,
    effective_date DATE NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PROPOSED_PENDING_CLIENT',
    reason TEXT NULL,
    estimate_id CHAR(36) NULL,
    requested_by CHAR(36) NULL,
    confirmed_at TIMESTAMP NULL,
    confirmed_by CHAR(36) NULL,
    justification_pdf_url TEXT NULL,
    justification_uploaded_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_self_employed_base_changes PRIMARY KEY (id),
    CONSTRAINT fk_se_base_changes_profile FOREIGN KEY (profile_id) REFERENCES self_employed_profiles (id),
    CONSTRAINT fk_se_base_changes_estimate FOREIGN KEY (estimate_id) REFERENCES self_employed_estimates (id),
    CONSTRAINT fk_se_base_changes_requested_by FOREIGN KEY (requested_by) REFERENCES user_accounts (id),
    CONSTRAINT fk_se_base_changes_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_se_base_changes_status CHECK (status IN (
        'PROPOSED_PENDING_CLIENT',
        'APPROVED_BY_CLIENT',
        'REJECTED_BY_CLIENT',
        'SUBMITTED_TO_SS',
        'CONFIRMED',
        'CANCELLED'
    )),
    INDEX ix_se_base_changes_profile_year (profile_id, year_number),
    INDEX ix_se_base_changes_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 2) Pre-onboarding RETA
--    Captura de datos del prospecto ANTES de darlo de alta como cliente.
--    Hace simulacion con 3 escenarios (optimista/realista/pesimista) y
--    propone tramo y base recomendados. No tiene aun self_employed_profile
--    porque por definicion es previo al alta; el dato vive en la asesoria.
-- ---------------------------------------------------------------------------
CREATE TABLE self_employed_preonboarding (
    id CHAR(36) NOT NULL,
    advisory_company_id CHAR(36) NOT NULL,
    prospect_company_id CHAR(36) NULL,
    prospect_name VARCHAR(180) NOT NULL,
    tax_identifier VARCHAR(32) NULL,
    activity_type VARCHAR(80) NULL,
    sector VARCHAR(80) NULL,
    iae_codes JSON NULL,
    estimated_monthly_income DECIMAL(14,2) NULL,
    monthly_fixed_expenses DECIMAL(14,2) NULL,
    variable_expenses_pct DECIMAL(5,2) NULL,
    has_employees BOOLEAN NOT NULL DEFAULT FALSE,
    monthly_employee_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    has_premises BOOLEAN NOT NULL DEFAULT FALSE,
    monthly_rent DECIMAL(14,2) NOT NULL DEFAULT 0,
    previously_self_employed BOOLEAN NOT NULL DEFAULT FALSE,
    last_registration_date DATE NULL,
    previous_net_yield DECIMAL(14,2) NULL,
    eligible_flat_rate BOOLEAN NULL,
    optimistic_result JSON NULL,
    realistic_result JSON NULL,
    pessimistic_result JSON NULL,
    recommended_bracket INT NULL,
    recommended_base DECIMAL(14,2) NULL,
    estimated_quota DECIMAL(14,2) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_self_employed_preonboarding PRIMARY KEY (id),
    CONSTRAINT fk_se_preonb_advisory FOREIGN KEY (advisory_company_id) REFERENCES companies (id),
    CONSTRAINT fk_se_preonb_prospect FOREIGN KEY (prospect_company_id) REFERENCES companies (id),
    CONSTRAINT ck_se_preonb_status CHECK (status IN (
        'DRAFT',
        'IN_REVIEW',
        'ACCEPTED',
        'REJECTED',
        'CONVERTED'
    )),
    INDEX ix_se_preonb_advisory_status (advisory_company_id, status),
    INDEX ix_se_preonb_prospect (prospect_company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
