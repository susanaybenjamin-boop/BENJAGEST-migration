-- ===========================================================================
-- V139 — Módulo Trabajos: employee_id OPCIONAL.
--
-- Un autónomo/empresario que trabaja SOLO (sin empleados) tiene que poder
-- registrar SUS trabajos: un trabajo sin empleado = el propio titular. Hasta
-- ahora employee_id era NOT NULL y lo impedía.
--
-- La FK fk_work_logs_employee se mantiene (no valida sobre NULL).
-- ===========================================================================

ALTER TABLE work_logs MODIFY COLUMN employee_id CHAR(36) NULL;
