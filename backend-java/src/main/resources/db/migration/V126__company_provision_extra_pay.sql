-- ===========================================================================
-- V126 — Política contable de la empresa: provisionar pagas extra mensualmente.
--
-- La provisión mensual de pagas extra no prorrateadas (640→465, criterio de
-- devengo) es lo correcto contablemente, pero algunas asesorías prefieren
-- contabilizar el gasto solo al pagar la paga (vía simplificada). Este flag
-- permite elegir; por defecto TRUE (la forma correcta por devengo).
-- ===========================================================================

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS provision_extra_pay BOOLEAN NOT NULL DEFAULT TRUE;
