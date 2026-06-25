-- ===========================================================================
-- V145 — Cuota fija de cotización de los contratos de FORMACIÓN EN ALTERNANCIA.
--
-- Bloque CONTRATO-MODALIDADES / CM-6 (Benjamin 2026-06-25, "completo por ley").
-- Los contratos formativos EN ALTERNANCIA (familia SEPE FORMATIVO, códigos
-- 421/521) NO cotizan por porcentajes sobre base, sino por una CUOTA ÚNICA
-- FIJA mensual (cuando el salario no supera la base mínima), que ya engloba
-- contingencias comunes, profesionales, desempleo, FOGASA, FP y MEI.
--
-- Orden PJC/297/2026 — cuantías 2026 (cotizando por la base mínima):
--   · Total mensual: 197,24 €  =  empresa 161,23 €  +  trabajador 36,01 €
--   · De ese total, el MEI (12,82 €) NO es bonificable.
-- (La PRÁCTICA PROFESIONAL —códigos 401/501, familia PRACTICAS— cotiza NORMAL
--  por porcentajes; NO usa esta tabla.)
--
-- No-code, por año: cuando cambien las cuantías solo se añade la fila del año
-- desde la pantalla de tipos de cotización. El cálculo de nómina aplica esta
-- cuota fija para los formativos en alternancia; si el año no está configurado
-- FALLA de forma ruidosa (no calcula con porcentajes que serían incorrectos).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS formacion_alternancia_quotas (
    year INT NOT NULL,
    employee_monthly DECIMAL(8,2) NOT NULL,   -- a cargo del trabajador (€/mes)
    employer_monthly DECIMAL(8,2) NOT NULL,   -- a cargo de la empresa (€/mes)
    mei_monthly DECIMAL(8,2) NOT NULL DEFAULT 0, -- MEI incluido en el total (no bonificable), informativo
    legal_reference VARCHAR(200) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_formacion_alternancia_quotas PRIMARY KEY (year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO formacion_alternancia_quotas
    (year, employee_monthly, employer_monthly, mei_monthly, legal_reference)
VALUES
 (2026, 36.01, 161.23, 12.82, 'Orden PJC/297/2026 (cuota fija formación en alternancia)');
