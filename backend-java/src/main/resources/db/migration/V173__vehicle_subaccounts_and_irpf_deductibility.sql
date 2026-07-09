-- ===========================================================================
-- V173 — Subcuentas de vehículo + flag de deducibilidad IRPF por cuenta.
--
-- Bloque IRPF-DED (Benjamin 2026-07-09). Origen: al comparar el modelo 130
-- REAL de Benjamin (1T/2T 2026, estimación directa simplificada, presentado
-- por SH Asesores) con el que calcula BENJAGEST, los gastos de VEHÍCULO
-- (combustible y reparación de la furgoneta) se contaban como deducibles en
-- IRPF, cuando la asesoría los excluye por no estar el vehículo afecto
-- EXCLUSIVAMENTE a la actividad (art. 22 y 29 Reglamento IRPF). Un gasto puede
-- ser deducible en IVA (303) pero NO en IRPF (130): es el caso clásico del
-- vehículo no afecto al 100%.
--
-- Diseño (decidido con Benjamin): NO automático por proveedor/palabra clave —
-- la afectación es un juicio del asesor. La deducibilidad la lleva la CUENTA:
--   · cuenta genérica (622/628/625)  → deducible en IRPF   (flag = 1)
--   · subcuenta de vehículo (62x1)   → NO deducible en IRPF (flag = 0)
-- El asesor decide moviendo el gasto (o el proveedor, vía regla aprendida) a
-- la subcuenta o a la genérica. El expense_deductible de cada factura hereda
-- el flag de su cuenta (lo sincroniza el cálculo del 130).
--
-- IDEMPOTENTE. COLLATE explícito en la temporal (MariaDB 11.4 cambió el
-- default del servidor — mismo motivo que V25/V44/V46).
-- ===========================================================================

-- 1) Flag de deducibilidad IRPF por cuenta, en la plantilla PERMANENTE
--    (instalaciones nuevas) y en las cuentas de cada empresa.
ALTER TABLE pgc_template
    ADD COLUMN irpf_deductible_default TINYINT(1) NOT NULL DEFAULT 1;

ALTER TABLE accounting_accounts
    ADD COLUMN irpf_deductible_default TINYINT(1) NOT NULL DEFAULT 1;

-- 2) Subcuentas de vehículo en la plantilla permanente. No deducibles en IRPF
--    por defecto (vehículo no afecto al 100%). El asesor las usa para separar
--    los gastos de vehículo de los genéricos deducibles.
INSERT IGNORE INTO pgc_template (code, name, account_type, irpf_deductible_default) VALUES
('6221', 'Reparación y conservación de vehículos', 'EXPENSE', 0),
('6281', 'Combustible de vehículos',               'EXPENSE', 0),
('6251', 'Seguro de vehículos',                     'EXPENSE', 0);

-- 3) Sembrar esas subcuentas en TODAS las empresas existentes (patrón V46).
--    INSERT IGNORE = idempotente: si ya existe (company_id, code), la omite.
CREATE TEMPORARY TABLE pgc_vehiculo_tmp (
    code VARCHAR(20) NOT NULL,
    name VARCHAR(180) NOT NULL,
    account_type VARCHAR(40) NOT NULL,
    irpf_deductible_default TINYINT(1) NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO pgc_vehiculo_tmp (code, name, account_type, irpf_deductible_default) VALUES
('6221', 'Reparación y conservación de vehículos', 'EXPENSE', 0),
('6281', 'Combustible de vehículos',               'EXPENSE', 0),
('6251', 'Seguro de vehículos',                     'EXPENSE', 0);

INSERT IGNORE INTO accounting_accounts
    (id, company_id, code, name, account_type, active, is_standard, irpf_deductible_default)
SELECT UUID(), c.id, t.code, t.name, t.account_type, TRUE, TRUE, t.irpf_deductible_default
  FROM companies c
 CROSS JOIN pgc_vehiculo_tmp t;

DROP TEMPORARY TABLE pgc_vehiculo_tmp;
