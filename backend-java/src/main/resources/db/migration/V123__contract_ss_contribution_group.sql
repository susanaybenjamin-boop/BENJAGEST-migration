-- ===========================================================================
-- V123 — Grupo de cotización (1-11) en el contrato.
--
-- La base mínima de cotización a la SS depende del grupo de cotización (1-11)
-- del trabajador (ver ss_contribution_group_bases, V121/V122). Hasta ahora el
-- contrato no guardaba ese dato (professional_group es del convenio, texto).
-- Añadimos la columna y damos por defecto el grupo 7 (Auxiliares
-- administrativos), el más común, para los contratos existentes.
-- ===========================================================================

ALTER TABLE employment_contracts
    ADD COLUMN IF NOT EXISTS ss_contribution_group SMALLINT NULL DEFAULT 7 AFTER at_ep_percent;

UPDATE employment_contracts SET ss_contribution_group = 7 WHERE ss_contribution_group IS NULL;
