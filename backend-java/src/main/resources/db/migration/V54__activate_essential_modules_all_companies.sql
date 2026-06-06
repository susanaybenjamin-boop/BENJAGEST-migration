-- ===========================================================================
-- V54__activate_essential_modules_all_companies.sql
--
-- Garantiza que todas las empresas tienen activos los módulos esenciales
-- para operar como cliente/empresario:
--   core, billing, purchases, accounting, tax, calendar, notifications,
--   labor, reports
--
-- El módulo 'advisory' sigue NO activándose automáticamente: es solo para
-- empresas INTERNAL (la asesoría) y el control de eso está en V8.
--
-- Causa raíz que motiva esta migración:
-- Hasta ahora V8 sembraba módulos para Marcos pero podía dejar 'accounting'
-- a 0 si el seed se hizo en un orden distinto al esperado, o si V7 cambió
-- el catálogo después. El usuario veía 403 al entrar a contabilidad sin
-- saber que el módulo estaba inactivo.
--
-- Esta migración es defensiva: NO desactiva nada, solo activa lo que falta.
-- Idempotente y segura.
-- ===========================================================================

-- INSERT IGNORE: si la fila (company_id, module_id) ya existe, no hace nada.
INSERT IGNORE INTO company_modules (id, company_id, module_id, active)
SELECT UUID(), c.id, m.id, TRUE
  FROM companies c
  CROSS JOIN module_catalog m
 WHERE m.slug IN ('core', 'billing', 'purchases', 'accounting', 'tax',
                  'calendar', 'notifications', 'labor', 'reports')
   AND m.active_in_catalog = TRUE;

-- UPDATE para los que SÍ existen pero están active=0: los activamos.
UPDATE company_modules cm
  JOIN module_catalog m ON m.id = cm.module_id
   SET cm.active = TRUE,
       cm.activated_at = CURRENT_TIMESTAMP,
       cm.deactivated_at = NULL
 WHERE m.slug IN ('core', 'billing', 'purchases', 'accounting', 'tax',
                  'calendar', 'notifications', 'labor', 'reports')
   AND cm.active = FALSE;
