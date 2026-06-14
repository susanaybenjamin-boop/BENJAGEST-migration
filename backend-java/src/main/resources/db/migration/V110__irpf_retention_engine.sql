-- ===========================================================================
-- V110 — Motor de retención IRPF como A3 (bloque IRPF-145).
--
-- Calcula el tipo de retención según el procedimiento general del Reglamento
-- IRPF (art. 80-89): base = retribución anual − SS − gastos − reducción por
-- rendimientos del trabajo; se aplica la escala de retención a la base y al
-- mínimo personal y familiar (datos del modelo 145); tipo = cuota / retribución.
--
-- Todo POR AÑO y editable (filosofía Benjamin: nada a fuego):
--   • irpf_retention_brackets: escala de retención (combinada estatal+autonómica).
--   • irpf_retention_params: parámetros (mínimos, reducciones) de un año.
--   • employee_irpf_data: datos del modelo 145 de cada empleado.
--
-- Valores 2026 (estables desde 2015 salvo escala). Si cambian, se edita el año.
-- ===========================================================================

-- Escala de retención: cada fila es el TRAMO que empieza en lower_limit.
CREATE TABLE IF NOT EXISTS irpf_retention_brackets (
    year INT NOT NULL,
    lower_limit DECIMAL(14,2) NOT NULL,
    rate DECIMAL(6,3) NOT NULL,
    CONSTRAINT pk_irpf_retention_brackets PRIMARY KEY (year, lower_limit)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO irpf_retention_brackets (year, lower_limit, rate) VALUES
    (2026,      0.00, 19.000),
    (2026,  12450.00, 24.000),
    (2026,  20200.00, 30.000),
    (2026,  35200.00, 37.000),
    (2026,  60000.00, 45.000),
    (2026, 300000.00, 47.000);

-- Parámetros (mínimo personal y familiar + reducciones).
CREATE TABLE IF NOT EXISTS irpf_retention_params (
    year INT NOT NULL,
    personal_min DECIMAL(14,2) NOT NULL DEFAULT 5550,
    personal_over65 DECIMAL(14,2) NOT NULL DEFAULT 1150,
    personal_over75 DECIMAL(14,2) NOT NULL DEFAULT 1400,
    descendant_1 DECIMAL(14,2) NOT NULL DEFAULT 2400,
    descendant_2 DECIMAL(14,2) NOT NULL DEFAULT 2700,
    descendant_3 DECIMAL(14,2) NOT NULL DEFAULT 4000,
    descendant_4plus DECIMAL(14,2) NOT NULL DEFAULT 4500,
    descendant_under3 DECIMAL(14,2) NOT NULL DEFAULT 2800,
    ascendant_over65 DECIMAL(14,2) NOT NULL DEFAULT 1150,
    ascendant_over75 DECIMAL(14,2) NOT NULL DEFAULT 1400,
    disability_33 DECIMAL(14,2) NOT NULL DEFAULT 3000,
    disability_65 DECIMAL(14,2) NOT NULL DEFAULT 9000,
    disability_mobility DECIMAL(14,2) NOT NULL DEFAULT 3000,
    expense_deduction DECIMAL(14,2) NOT NULL DEFAULT 2000,
    work_reduction_max DECIMAL(14,2) NOT NULL DEFAULT 7302,
    work_reduction_threshold1 DECIMAL(14,2) NOT NULL DEFAULT 14852,
    work_reduction_threshold2 DECIMAL(14,2) NOT NULL DEFAULT 17673.52,
    work_reduction_factor DECIMAL(8,4) NOT NULL DEFAULT 1.7500,
    legal_reference VARCHAR(200) NULL,
    CONSTRAINT pk_irpf_retention_params PRIMARY KEY (year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO irpf_retention_params (year, legal_reference)
VALUES (2026, 'Reglamento IRPF art. 80-89');

-- Datos del modelo 145 de cada empleado.
CREATE TABLE IF NOT EXISTS employee_irpf_data (
    employee_id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    family_situation INT NOT NULL DEFAULT 3,          -- 1 monoparental, 2 conyuge sin rentas, 3 resto
    spouse_nif VARCHAR(20) NULL,
    descendants INT NOT NULL DEFAULT 0,               -- nº hijos < 25 (o discapacitados)
    descendants_under3 INT NOT NULL DEFAULT 0,        -- de los anteriores, < 3 años
    descendants_disability33 INT NOT NULL DEFAULT 0,  -- con discapacidad 33-64%
    descendants_disability65 INT NOT NULL DEFAULT 0,  -- con discapacidad >= 65%
    exclusive_custody BOOLEAN NOT NULL DEFAULT TRUE,  -- convivencia/cómputo exclusivo (si no, 50%)
    ascendants_over65 INT NOT NULL DEFAULT 0,
    ascendants_over75 INT NOT NULL DEFAULT 0,
    own_disability VARCHAR(10) NOT NULL DEFAULT 'NONE', -- NONE / D33 / D65
    own_mobility BOOLEAN NOT NULL DEFAULT FALSE,        -- movilidad reducida / ayuda terceros
    taxpayer_over65 BOOLEAN NOT NULL DEFAULT FALSE,
    taxpayer_over75 BOOLEAN NOT NULL DEFAULT FALSE,
    contract_under_year BOOLEAN NOT NULL DEFAULT FALSE,
    geographic_mobility BOOLEAN NOT NULL DEFAULT FALSE,
    mortgage_before2013 BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_employee_irpf_data PRIMARY KEY (employee_id),
    CONSTRAINT fk_eid_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_eid_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    CONSTRAINT ck_eid_family CHECK (family_situation IN (1, 2, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
