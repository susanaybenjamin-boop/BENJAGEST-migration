-- ===========================================================================
-- V174 — DEDUC: deducibilidad parcial por PORCENTAJE (IVA e IRPF
-- independientes) + reglas por proveedor.
--
-- Decidido con Benjamin 2026-07-09 tras estudiar la competencia (Contasol,
-- a3, Quipu, inmatic) y las FAQ AEAT del libro registro: la AEAT distingue
-- cuota SOPORTADA / cuota DEDUCIBLE (IVA) / importe deducible (IRPF), que
-- son tres datos distintos. Caso canónico: vehículo no afecto al 100% =
-- IVA 50% (art. 95.Tres LIVA) + IRPF 0% (art. 22/29 Reglamento IRPF).
--
--   · vat_deductible_percent ya existía (V172) y el 303 ya lo respeta.
--   · irpf_deductible_percent NUEVO: NULL = hereda de la CUENTA del gasto
--     (modelo IRPF-DED de V173: subcuenta vehículo=0, genérica=100);
--     un valor 0-100 = decisión explícita de la asesoría para ESA factura.
--   · supplier_deductibility_rules: regla por proveedor que solo PRECARGA
--     los % al crear/importar un gasto de ese NIF (estilo inmatic). La
--     asesoría siempre puede corregir factura a factura.
--
-- IDEMPOTENTE donde procede. COLLATE explícito (MariaDB 11.4 cambió el
-- default del servidor — mismo motivo que V25/V44/V46).
-- ===========================================================================

ALTER TABLE purchase_invoices
    ADD COLUMN irpf_deductible_percent DECIMAL(5,2) NULL;

CREATE TABLE IF NOT EXISTS supplier_deductibility_rules (
    id                       CHAR(36)      NOT NULL PRIMARY KEY,
    company_id               CHAR(36)      NOT NULL,
    supplier_nif             VARCHAR(20)   NOT NULL,
    vat_deductible_percent   DECIMAL(5,2)  NOT NULL DEFAULT 100.00,
    irpf_deductible_percent  DECIMAL(5,2)  NOT NULL DEFAULT 100.00,
    label                    VARCHAR(120)  NULL,
    created_by_user_id       CHAR(36)      NULL,
    created_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                           ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_deduc_rule (company_id, supplier_nif)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
