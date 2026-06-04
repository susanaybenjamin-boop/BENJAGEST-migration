-- ===========================================================================
-- V29__employees_and_contracts_enrichment.sql
--
-- L1 — Empleados + contratos. La V2 ya creó las tablas `employees` y
-- `employment_contracts` con lo mínimo. Para poder hacer nóminas reales
-- y modelos 111/190, necesitamos los campos de:
--
--   - Identidad fiscal: NIF, NUSS (Numero de Seguridad Social),
--     fecha de nacimiento, situacion familiar.
--   - Direccion postal: para el certificado anual de retenciones.
--   - IBAN para domiciliacion de nomina.
--   - Datos del contrato: codigo SEPE, convenio, categoria, pagas
--     extras, vacaciones, IRPF retenido aplicable.
--
-- Todo con IF NOT EXISTS porque V2 puede haberse migrado distinto en
-- entornos antiguos.
-- ===========================================================================

ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS birth_date DATE NULL AFTER full_name,
    ADD COLUMN IF NOT EXISTS social_security_number VARCHAR(20) NULL AFTER tax_identifier,
    ADD COLUMN IF NOT EXISTS gender VARCHAR(20) NULL AFTER birth_date,
    ADD COLUMN IF NOT EXISTS marital_status VARCHAR(30) NULL AFTER gender,
    ADD COLUMN IF NOT EXISTS dependent_children INT NULL AFTER marital_status,
    ADD COLUMN IF NOT EXISTS dependent_disabled INT NULL AFTER dependent_children,
    ADD COLUMN IF NOT EXISTS address_line VARCHAR(200) NULL AFTER phone,
    ADD COLUMN IF NOT EXISTS city VARCHAR(120) NULL AFTER address_line,
    ADD COLUMN IF NOT EXISTS province VARCHAR(80) NULL AFTER city,
    ADD COLUMN IF NOT EXISTS postal_code VARCHAR(10) NULL AFTER province,
    ADD COLUMN IF NOT EXISTS country VARCHAR(80) NULL DEFAULT 'Espana' AFTER postal_code,
    ADD COLUMN IF NOT EXISTS iban VARCHAR(34) NULL AFTER country,
    ADD COLUMN IF NOT EXISTS ss_regime VARCHAR(40) NULL AFTER work_type,
    ADD COLUMN IF NOT EXISTS hire_date DATE NULL AFTER ss_regime,
    ADD COLUMN IF NOT EXISTS termination_date DATE NULL AFTER hire_date,
    ADD COLUMN IF NOT EXISTS termination_reason VARCHAR(120) NULL AFTER termination_date;

-- NIF unico por empresa cuando esta informado (no podemos hacer UNIQUE
-- directo porque hay NULLs). Index para busquedas y para la check
-- desde la app si se necesita.
ALTER TABLE employees
    ADD INDEX IF NOT EXISTS ix_employees_nif (company_id, tax_identifier),
    ADD INDEX IF NOT EXISTS ix_employees_nuss (company_id, social_security_number);

ALTER TABLE employment_contracts
    ADD COLUMN IF NOT EXISTS sepe_contract_code VARCHAR(10) NULL AFTER contract_type,
    ADD COLUMN IF NOT EXISTS collective_agreement VARCHAR(200) NULL AFTER sepe_contract_code,
    ADD COLUMN IF NOT EXISTS professional_category VARCHAR(120) NULL AFTER collective_agreement,
    ADD COLUMN IF NOT EXISTS professional_group VARCHAR(40) NULL AFTER professional_category,
    ADD COLUMN IF NOT EXISTS annual_bonuses INT NULL DEFAULT 2 AFTER gross_salary,
    ADD COLUMN IF NOT EXISTS vacation_days INT NULL DEFAULT 30 AFTER annual_bonuses,
    ADD COLUMN IF NOT EXISTS irpf_percent DECIMAL(5,2) NULL AFTER vacation_days,
    ADD COLUMN IF NOT EXISTS workplace_address VARCHAR(200) NULL AFTER irpf_percent,
    ADD COLUMN IF NOT EXISTS termination_reason VARCHAR(120) NULL AFTER status;

-- ===========================================================================
-- payslips: cabecera del recibo de salario (slice futuro detalla
-- conceptos). Por ahora dejamos la cabecera para poder listar las
-- nominas que se han generado y vincular al modelo 111.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS payslips (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    contract_id CHAR(36) NULL,
    period_year INT NOT NULL,
    period_month INT NOT NULL,
    payslip_type VARCHAR(30) NOT NULL DEFAULT 'MONTHLY',
    gross_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    ss_employee_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    irpf_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    other_deductions DECIMAL(14,2) NOT NULL DEFAULT 0,
    net_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    paid_at DATE NULL,
    pdf_path VARCHAR(500) NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_payslips PRIMARY KEY (id),
    CONSTRAINT uk_payslips_period UNIQUE (company_id, employee_id, period_year, period_month, payslip_type),
    CONSTRAINT fk_payslips_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_payslips_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_payslips_contract FOREIGN KEY (contract_id) REFERENCES employment_contracts (id),
    CONSTRAINT ck_payslips_month CHECK (period_month BETWEEN 1 AND 12),
    CONSTRAINT ck_payslips_type CHECK (payslip_type IN ('MONTHLY', 'EXTRA_SUMMER', 'EXTRA_CHRISTMAS', 'BONUS', 'SETTLEMENT')),
    CONSTRAINT ck_payslips_status CHECK (status IN ('DRAFT', 'CALCULATED', 'PAID', 'CANCELLED')),
    INDEX ix_payslips_company_period (company_id, period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
