-- ===========================================================================
-- V136 — INC-1: incidencias de nómina por empleado y periodo.
--
-- Estructura y PERSISTE las incidencias que afectan al cálculo de una nómina
-- (hoy solo existían como `extraConcepts` inline en el diálogo de calcular,
-- sin guardarse). Se asocian a (empleado, año, mes) — NO a una fila payslips,
-- porque la nómina puede no existir aún cuando se registran las incidencias.
--
-- Catálogo de tipos (inspirado en A3Nom/Nóminasol + lo que exige la ley):
--   OVERTIME   horas extra. subtype STRUCTURAL (estructurales / fuerza mayor,
--              cotización adicional 14%) | NORMAL (resto, 28,30%). Tributan 100%.
--              La cotización adicional la calcula INC-2 (tabla por año, no-code).
--   COMPLEMENT complemento variable del periodo (importe). cotiza/tributa según
--              marca. Equivale a los extraConcepts actuales, pero guardado.
--   ABSENCE    ausencia/descuento. subtype JUSTIFIED_PAID | JUSTIFIED_UNPAID |
--              UNJUSTIFIED. Resta devengo y (INC-3) reduce base proporcional.
--   DEDUCTION  descuento puntual (anticipos, embargos…). No cotiza, no tributa.
--   OTHER      cajón flexible (importe + flags).
--
-- Aditiva: no toca seeds ni tablas existentes.
-- ===========================================================================

CREATE TABLE nomina_incidencias (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    period_year INT NOT NULL,
    period_month INT NOT NULL,
    -- OVERTIME | COMPLEMENT | ABSENCE | DEDUCTION | OTHER
    kind VARCHAR(20) NOT NULL,
    -- overtime: STRUCTURAL | NORMAL ; absence: JUSTIFIED_PAID | JUSTIFIED_UNPAID | UNJUSTIFIED
    subtype VARCHAR(30) NULL,
    concept VARCHAR(200) NOT NULL,
    -- Horas (extra o de ausencia por horas) y su precio/hora si se valora así.
    hours DECIMAL(8,2) NULL,
    unit_price DECIMAL(12,4) NULL,
    -- Días (ausencia por días).
    days DECIMAL(6,2) NULL,
    -- Importe directo o calculado (hours*unit_price). Para ABSENCE/DEDUCTION es el
    -- importe a RESTAR (se guarda positivo; el signo lo aplica el motor por `kind`).
    amount DECIMAL(14,2) NULL,
    cotizes BOOLEAN NOT NULL DEFAULT TRUE,
    taxable BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT NULL,
    -- MANUAL | AUTO (auto-detectada de bajas/permisos en INC-3).
    source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    -- Referencia a la baja/permiso de origen si AUTO (sin FK, para no acoplar).
    source_ref_id CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_nomina_incidencias PRIMARY KEY (id),
    CONSTRAINT fk_ninc_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_ninc_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    CONSTRAINT ck_ninc_kind CHECK (kind IN ('OVERTIME', 'COMPLEMENT', 'ABSENCE', 'DEDUCTION', 'OTHER')),
    CONSTRAINT ck_ninc_month CHECK (period_month BETWEEN 1 AND 12),
    CONSTRAINT ck_ninc_source CHECK (source IN ('MANUAL', 'AUTO')),
    INDEX ix_ninc_emp_period (company_id, employee_id, period_year, period_month),
    INDEX ix_ninc_company_period (company_id, period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
