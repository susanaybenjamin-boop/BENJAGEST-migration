-- ===========================================================================
-- V112 — Prorrateo de pagas extra a nivel de contrato (bloque NOM).
--
-- Hasta ahora el prorrateo (12 pagas) vs. pagas extra separadas (14 pagas)
-- se decidia con una casilla en "calcular nomina". Ahora se guarda en el
-- contrato para que esa casilla aparezca marcada/desmarcada segun el
-- contrato del empleado.
--
-- Default legal: NO prorrateado (14 pagas, art. 31 ET) salvo que el convenio
-- lo permita y se marque.
-- ===========================================================================

ALTER TABLE employment_contracts
    ADD COLUMN IF NOT EXISTS extras_prorated BOOLEAN NOT NULL DEFAULT FALSE AFTER annual_bonuses;
