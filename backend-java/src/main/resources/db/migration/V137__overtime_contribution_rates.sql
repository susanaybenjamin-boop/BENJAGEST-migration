-- ===========================================================================
-- V137 — INC-2: tipos de cotización ADICIONAL por horas extraordinarias.
--
-- Las horas extra NO cotizan en la base de contingencias comunes: tienen una
-- cotización adicional propia (LGSS art. 149 / Orden de cotización anual), con
-- dos tipos según el tipo de hora:
--   ESTRUCTURALES / fuerza mayor : 14,00%  (empresa 12,00% + trabajador 2,00%)
--   NO ESTRUCTURALES (resto)     : 28,30%  (empresa 23,60% + trabajador 4,70%)
--
-- Tabla NO-CODE editable por año (mismo patrón que ss_contribution_rates,
-- V108/V121): el motor lee la fila del año (fallback al último año <= pedido).
-- Las horas extra TRIBUTAN IRPF al 100% (eso lo aplica el motor, no esta tabla).
--
-- Aditiva: no toca seeds ni tablas existentes.
-- ===========================================================================

CREATE TABLE overtime_contribution_rates (
    year INT NOT NULL,
    -- Estructurales / fuerza mayor (14% = 12 + 2).
    structural_employer DECIMAL(6,3) NOT NULL,
    structural_employee DECIMAL(6,3) NOT NULL,
    -- No estructurales (28,30% = 23,60 + 4,70).
    normal_employer DECIMAL(6,3) NOT NULL,
    normal_employee DECIMAL(6,3) NOT NULL,
    legal_reference VARCHAR(200) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_overtime_contribution_rates PRIMARY KEY (year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Semilla 2026 (cifras vigentes; editables sin tocar código si cambian).
INSERT INTO overtime_contribution_rates
       (year, structural_employer, structural_employee, normal_employer, normal_employee, legal_reference)
VALUES (2026, 12.000, 2.000, 23.600, 4.700,
        'Horas extra: estructurales/fuerza mayor 14% (12+2); resto 28,30% (23,60+4,70). LGSS art. 149.');
