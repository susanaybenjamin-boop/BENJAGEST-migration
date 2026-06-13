-- ===========================================================================
-- V107 — Desglose de devengos por concepto (bloque DEV-DESGLOSE).
--
-- Benjamin (2026-06-13): quiere el recibo como Nomio, con el salario
-- descompuesto en "Salario base" + complementos libres (mejora voluntaria,
-- antigüedad, plus transporte, etc.), cada uno con su importe y si cotiza
-- a la SS / tributa por IRPF.
--
--   • contract_salary_items: conceptos salariales de un contrato. El
--     `gross_salary` del contrato pasa a ser la SUMA de estos conceptos
--     (se recalcula al guardar). Para contratos sin conceptos (legacy) se
--     mantiene el comportamiento anterior (un único bloque = gross_salary).
--   • payslip_lines: líneas de devengo de una nómina concreta (snapshot
--     legal de lo pagado ese mes), generadas al calcular.
--
-- kind:
--   SALARY_BASE   — salario base (uno por contrato).
--   COMPLEMENT    — complemento salarial (cotiza y tributa por defecto).
--   NON_SALARIAL  — percepción no salarial (dietas, locomoción…): puede no
--                   cotizar / no tributar según los flags.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS contract_salary_items (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    contract_id CHAR(36) NOT NULL,
    concept_name VARCHAR(120) NOT NULL,
    kind VARCHAR(20) NOT NULL DEFAULT 'COMPLEMENT',
    annual_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    cotizes BOOLEAN NOT NULL DEFAULT TRUE,
    taxable BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_salary_items PRIMARY KEY (id),
    CONSTRAINT fk_csi_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_csi_contract FOREIGN KEY (contract_id) REFERENCES employment_contracts (id) ON DELETE CASCADE,
    CONSTRAINT ck_csi_kind CHECK (kind IN ('SALARY_BASE', 'COMPLEMENT', 'NON_SALARIAL')),
    INDEX ix_csi_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payslip_lines (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    payslip_id CHAR(36) NOT NULL,
    concept_name VARCHAR(120) NOT NULL,
    kind VARCHAR(20) NOT NULL DEFAULT 'COMPLEMENT',
    amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_payslip_lines PRIMARY KEY (id),
    CONSTRAINT fk_pl_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_pl_payslip FOREIGN KEY (payslip_id) REFERENCES payslips (id) ON DELETE CASCADE,
    INDEX ix_pl_payslip (payslip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
