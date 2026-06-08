-- =============================================================================
-- V75 — CTR-3 plantillas reutilizables + CTR-7 anexos vinculados + extras
--      necesarios para CTR-4 (PDF) y CTR-5 (XML).
--
-- 1) contract_templates           — plantillas guardadas por OWNER para
--                                   acelerar nuevos contratos. Capturan los
--                                   campos del wizard (tipo SEPE, convenio,
--                                   salario, anexos seleccionados…).
--
-- 2) contract_clause_links        — anexos/cláusulas built-in o custom
--                                   vinculados a un contrato concreto.
--
-- 3) contract_free_clauses        — cláusulas/anexos REDACTADOS A MANO
--                                   por el OWNER al firmar un contrato
--                                   concreto. No salen del catálogo.
--
-- 4) ALTER employment_contracts   — añade columnas necesarias para CTR-4/5:
--                                     pdf_model, agreement_id, category_id,
--                                     probation_days, workplace_postal_code,
--                                     workplace_city, workplace_province,
--                                     pdf_path, xml_path, sepe_sent_at.
--
-- =============================================================================

-- ===========================================================================
-- 1) contract_templates
-- ===========================================================================
CREATE TABLE contract_templates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(500) NULL,
    -- Datos pre-rellenados del wizard:
    sepe_contract_code VARCHAR(4) NULL,
    contract_type VARCHAR(40) NULL,
    collective_agreement_id CHAR(36) NULL,
    professional_category_id CHAR(36) NULL,
    professional_group VARCHAR(40) NULL,
    weekly_hours DECIMAL(5,2) NULL,
    gross_salary DECIMAL(10,2) NULL,
    annual_bonuses INT NULL,
    vacation_days INT NULL,
    irpf_percent DECIMAL(5,2) NULL,
    probation_days INT NULL,
    workplace_address VARCHAR(300) NULL,
    -- JSON array de clause codes preferidos por la plantilla.
    -- Ej: ["CONFIDENTIALITY_STANDARD","GEOLOCATION_GDPR"]
    clause_codes TEXT NULL,
    -- Preferencia de modelo PDF al firmar contratos desde la plantilla
    pdf_model VARCHAR(20) NULL,
    is_built_in BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_templates PRIMARY KEY (id),
    CONSTRAINT fk_ctpl_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE,
    CONSTRAINT fk_ctpl_agreement FOREIGN KEY (collective_agreement_id)
        REFERENCES collective_agreements (id),
    CONSTRAINT fk_ctpl_category FOREIGN KEY (professional_category_id)
        REFERENCES professional_categories (id),
    CONSTRAINT ck_ctpl_pdf_model CHECK (pdf_model IS NULL OR pdf_model IN ('UNIFIED_2022', 'BY_CODE')),
    INDEX ix_ctpl_company_active (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================================================
-- 2) contract_clause_links — cláusulas/anexos del catálogo vinculados
-- ===========================================================================
CREATE TABLE contract_clause_links (
    id CHAR(36) NOT NULL,
    contract_id CHAR(36) NOT NULL,
    clause_template_id CHAR(36) NOT NULL,
    -- Orden de aparición del anexo en el PDF final.
    sort_order INT NOT NULL DEFAULT 100,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_clause_links PRIMARY KEY (id),
    CONSTRAINT fk_clinks_contract FOREIGN KEY (contract_id)
        REFERENCES employment_contracts (id) ON DELETE CASCADE,
    CONSTRAINT fk_clinks_clause FOREIGN KEY (clause_template_id)
        REFERENCES contract_clause_templates (id),
    CONSTRAINT uk_clinks_contract_clause UNIQUE (contract_id, clause_template_id),
    INDEX ix_clinks_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================================================
-- 3) contract_free_clauses — texto libre redactado al firmar
-- ===========================================================================
CREATE TABLE contract_free_clauses (
    id CHAR(36) NOT NULL,
    contract_id CHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 200,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_free_clauses PRIMARY KEY (id),
    CONSTRAINT fk_cfree_contract FOREIGN KEY (contract_id)
        REFERENCES employment_contracts (id) ON DELETE CASCADE,
    INDEX ix_cfree_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================================================
-- 4) ALTER employment_contracts — columnas necesarias para CTR-4 / CTR-5
-- ===========================================================================
ALTER TABLE employment_contracts
    ADD COLUMN collective_agreement_id CHAR(36) NULL AFTER collective_agreement,
    ADD COLUMN professional_category_id CHAR(36) NULL AFTER professional_category,
    ADD COLUMN probation_days INT NULL AFTER vacation_days,
    ADD COLUMN workplace_postal_code VARCHAR(10) NULL AFTER workplace_address,
    ADD COLUMN workplace_city VARCHAR(120) NULL AFTER workplace_postal_code,
    ADD COLUMN workplace_province VARCHAR(120) NULL AFTER workplace_city,
    ADD COLUMN pdf_model VARCHAR(20) NULL AFTER status,
    ADD COLUMN pdf_path VARCHAR(500) NULL AFTER pdf_model,
    ADD COLUMN xml_path VARCHAR(500) NULL AFTER pdf_path,
    ADD COLUMN sepe_sent_at TIMESTAMP NULL AFTER xml_path,
    ADD CONSTRAINT fk_contracts_agreement FOREIGN KEY (collective_agreement_id)
        REFERENCES collective_agreements (id),
    ADD CONSTRAINT fk_contracts_category FOREIGN KEY (professional_category_id)
        REFERENCES professional_categories (id),
    ADD CONSTRAINT ck_contracts_pdf_model CHECK (pdf_model IS NULL OR pdf_model IN ('UNIFIED_2022', 'BY_CODE'));
