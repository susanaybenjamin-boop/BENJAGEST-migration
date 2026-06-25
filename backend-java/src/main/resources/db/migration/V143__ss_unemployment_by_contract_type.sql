-- ===========================================================================
-- V143 — Tipos de cotización por DESEMPLEO según el tipo de contrato.
--
-- El desempleo NO es igual para todos: varía por modalidad (bloque
-- CONTRATO-MODALIDADES, petición Benjamin 2026-06-25). Orden PJC/297/2026:
--   · Indefinido (fijos):  7,05 %  → 5,50 % empresa + 1,55 % trabajador
--   · Temporal (eventual): 8,30 %  → 6,70 % empresa + 1,60 % trabajador
--
-- ss_contribution_rates ya tiene ee/er_unemployment (= el INDEFINIDO). Aquí
-- añadimos las columnas del TEMPORAL. No-code: editables por año desde la
-- pantalla de tipos de cotización. El DEFAULT rellena la fila 2026 existente.
-- ===========================================================================

ALTER TABLE ss_contribution_rates
    ADD COLUMN ee_unemployment_temporal DECIMAL(5,2) NOT NULL DEFAULT 1.60 AFTER ee_unemployment,
    ADD COLUMN er_unemployment_temporal DECIMAL(5,2) NOT NULL DEFAULT 6.70 AFTER er_unemployment;
