-- ===========================================================================
-- V36__employee_geolocation.sql
--
-- EMP-GEO: campo geolocation_enabled por empleado.
--
-- Decisión:
--   - Por defecto FALSE (privacy by default).
--   - El admin lo activa al editar el empleado.
--   - Cuando se active el fichaje desde móvil/web con GPS, este flag
--     determina si se pide o no la posición y si se anexa al evento.
--   - Hoy es informativo; la validación con radio desde centro de
--     trabajo + límite de tolerancia llegará en el slice de geo
--     completo (futuro, ver TC-GEO en backlog).
-- ===========================================================================

ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS geolocation_enabled BOOLEAN NOT NULL DEFAULT FALSE;
