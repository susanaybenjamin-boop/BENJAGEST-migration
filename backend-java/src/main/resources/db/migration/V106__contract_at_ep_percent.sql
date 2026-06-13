-- ===========================================================================
-- V106 — Tipo de cotización AT/EP (accidentes de trabajo y enfermedad
-- profesional) por contrato.
--
-- Bloque NOM (ciclo mensual de nómina). El tipo AT/EP depende de la
-- actividad (CNAE) según la tarifa de primas (DA 4ª Ley 42/2006). Como
-- un mismo cliente puede tener empleados en actividades distintas,
-- Benjamin decidió (2026-06-13) que el tipo se configure POR CONTRATO.
--
-- Default 1,50 % (oficinas/servicios, código CNAE genérico "a"). El
-- asesor lo ajusta según la actividad real del puesto.
-- ===========================================================================

ALTER TABLE employment_contracts
    ADD COLUMN IF NOT EXISTS at_ep_percent DECIMAL(5,2) NULL DEFAULT 1.50 AFTER irpf_percent;
