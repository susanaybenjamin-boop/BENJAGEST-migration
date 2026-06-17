-- ===========================================================================
-- V125 — VIGENCIAS del contrato (condiciones con fecha de efecto) — VIG-1.
--
-- Modelo de ascenso/cambio de condiciones (decisión Benjamin tras barrido legal
-- + competencia A3Nom/Nóminasol/Factorial): el ascenso NO es un contrato nuevo,
-- es una novación del MISMO contrato. Las condiciones que pueden variar en el
-- tiempo (categoría, grupo de cotización, salario, jornada, IRPF, AT/EP…) se
-- guardan como VIGENCIAS con fecha de efecto, append-only. La nómina de cada
-- periodo usa la vigencia VIGENTE a la fecha del periodo → las nóminas ya
-- emitidas quedan intactas y las futuras usan los datos nuevos. La antigüedad
-- (start_date/seniority_date) vive en el contrato, NO en la vigencia: un ascenso
-- nunca la reinicia.
--
-- Datos FIJOS del contrato (no versionados): contract_type, sepe_contract_code,
-- start_date, seniority_date, end_date, status, workplace_address.
-- Los complementos salariales (contract_salary_items) NO se versionan todavía
-- (limitación conocida: el desglose usa los items actuales; el driver principal,
-- gross_salary, sí se versiona). Refinamiento futuro si hace falta histórico fino.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS contract_vigencias (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    contract_id CHAR(36) NOT NULL,
    effective_from DATE NOT NULL,
    -- Condiciones versionadas (espejo de las columnas variables del contrato).
    professional_group VARCHAR(40) NULL,
    professional_category VARCHAR(200) NULL,
    professional_category_id CHAR(36) NULL,
    ss_contribution_group SMALLINT NULL,
    gross_salary DECIMAL(12,2) NULL,
    weekly_hours DECIMAL(6,2) NULL,
    annual_bonuses INT NULL,
    extras_prorated BOOLEAN NOT NULL DEFAULT FALSE,
    vacation_days INT NULL,
    irpf_percent DECIMAL(6,2) NULL,
    at_ep_percent DECIMAL(6,2) NULL,
    -- Motivo del cambio (p.ej. "Alta inicial", "Ascenso a Oficial de 1ª").
    change_reason VARCHAR(200) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_vigencias PRIMARY KEY (id),
    CONSTRAINT uk_contract_vigencias_eff UNIQUE (contract_id, effective_from),
    CONSTRAINT fk_contract_vigencias_contract FOREIGN KEY (contract_id)
        REFERENCES employment_contracts (id) ON DELETE CASCADE,
    INDEX ix_contract_vigencias_lookup (contract_id, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill: una vigencia inicial por contrato existente, con efecto desde su
-- fecha de inicio y los valores actuales del contrato.
INSERT INTO contract_vigencias
    (id, company_id, contract_id, effective_from, professional_group,
     professional_category, professional_category_id, ss_contribution_group,
     gross_salary, weekly_hours, annual_bonuses, extras_prorated, vacation_days,
     irpf_percent, at_ep_percent, change_reason)
SELECT UUID(), c.company_id, c.id, c.start_date, c.professional_group,
       c.professional_category, c.professional_category_id, c.ss_contribution_group,
       c.gross_salary, c.weekly_hours, c.annual_bonuses, c.extras_prorated,
       c.vacation_days, c.irpf_percent, c.at_ep_percent, 'Alta inicial'
  FROM employment_contracts c
 WHERE NOT EXISTS (
       SELECT 1 FROM contract_vigencias v WHERE v.contract_id = c.id
 );
