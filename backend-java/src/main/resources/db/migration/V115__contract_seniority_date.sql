-- ===========================================================================
-- V115 — Fecha de antigüedad reconocida del contrato (bloque CICLO-VIDA).
--
-- Para el cálculo de la indemnización por despido, la antigüedad puede ser
-- anterior al inicio del contrato vigente cuando hay contratos sucesivos
-- (encadenamiento). Si se rellena, prevalece sobre start_date para la
-- antigüedad; si es NULL, se usa start_date.
-- ===========================================================================

ALTER TABLE employment_contracts
    ADD COLUMN IF NOT EXISTS seniority_date DATE NULL AFTER start_date;
