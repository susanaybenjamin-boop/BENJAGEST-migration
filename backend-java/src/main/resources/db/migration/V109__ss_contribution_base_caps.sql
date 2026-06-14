-- ===========================================================================
-- V109 — Topes de cotización (base máxima / mínima) por año (bloque A3-TOPES).
--
-- La base de cotización tiene un tope MÁXIMO mensual (igual para todos) y un
-- tope MÍNIMO (que en realidad depende del grupo de cotización; aquí lo
-- dejamos como un mínimo global configurable, opcional). El cálculo de
-- nóminas limita la base de cotización a [mínimo, máximo].
--
-- 2026 (Orden PJC/297/2026): tope máximo 5.101,20 €/mes. El mínimo se deja a
-- 0 (sin tope inferior) por defecto; el asesor puede fijarlo por año si lo
-- necesita. Van en la tabla por año para no tocar código cuando cambien.
-- ===========================================================================

ALTER TABLE ss_contribution_rates
    ADD COLUMN IF NOT EXISTS base_max_monthly DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER default_at_ep,
    ADD COLUMN IF NOT EXISTS base_min_monthly DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER base_max_monthly;

UPDATE ss_contribution_rates
   SET base_max_monthly = 5101.20
 WHERE year = 2026 AND base_max_monthly = 0;
