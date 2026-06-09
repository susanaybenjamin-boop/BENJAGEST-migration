-- V80 — Activar TODOS los módulos no-asesoría para Marcos Construcciones SL.
--
-- Causa raíz del bug Benjamin 2026-06-09: V8 sembró Marcos con solo un
-- kit minimalista ('core', 'billing', 'purchases', 'accounting', 'tax',
-- 'calendar', 'notifications'). Faltaban:
--
-- - settings → la pestaña Auditoría tiene @RequiresModule("settings")
--   y devolvía 403 → tabla auditoría vacía.
-- - labor → pestaña Laboral no aparecía → ni calendario laboral ni bajas
--   ni cotizaciones SS.
-- - team → módulo Equipo no aparecía.
-- - documents, reports → sub-módulos que sí debería tener un empresario.
--
-- Esta migración activa TODOS los módulos del catálogo que NO sean
-- advisory_only=TRUE para la empresa de Marcos, de forma idempotente
-- (NOT EXISTS evita duplicar si ya existían).
--
-- Si en el futuro se añade una nueva empresa seedea-da, hacer lo mismo
-- en su V correspondiente.

INSERT INTO company_modules (id, company_id, module_id, active)
SELECT UUID(),
       '33333333-3333-3333-3333-333333333333',
       m.id,
       TRUE
  FROM module_catalog m
 WHERE (m.advisory_only IS NULL OR m.advisory_only = FALSE)
   AND NOT EXISTS (
       SELECT 1 FROM company_modules cm
        WHERE cm.company_id = '33333333-3333-3333-3333-333333333333'
          AND cm.module_id = m.id
   );
