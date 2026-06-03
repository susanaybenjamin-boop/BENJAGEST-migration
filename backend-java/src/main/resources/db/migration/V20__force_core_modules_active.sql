-- ===========================================================================
-- V20__force_core_modules_active.sql
--
-- BUGFIX: hasta esta version era posible desactivar la categoria 'core'
-- desde la UI. La cascada `setActive(child, false)` por cada sub-modulo
-- de la categoria saltaba la defensa existente (que solo bloqueaba el
-- slug 'settings' directo) y desactivaba 'settings' por la puerta de
-- atras. Resultado: el endpoint /api/settings/modules (anotado con
-- @RequiresModule("settings")) devolvia 403, y la empresa se quedaba
-- sin forma de volver a re-activarlo desde la propia pantalla.
--
-- Esta migracion repara cualquier empresa que ya este en ese estado:
--   1. Asegura que TODAS las empresas tienen las 4 filas del subarbol
--      core (core / customers / settings / users) creadas. Si no
--      existen, las crea con active=TRUE.
--   2. Para las que existen pero estan con active=FALSE, las pone
--      a TRUE y limpia la fecha de desactivacion.
--
-- El backend (CompanyModulesService a partir de este slice) ya no
-- expone estos modulos en el catalogo (filtrados de list) y rechaza
-- cualquier intento de tocarlos (slug 'core' o cualquier hijo).
-- ===========================================================================

-- 1) Crear filas ausentes para todas las empresas — para core y sus 3 hijos.
INSERT INTO company_modules (id, company_id, module_id, active)
SELECT UUID(), c.id, mc.id, TRUE
  FROM companies c
  CROSS JOIN module_catalog mc
 WHERE mc.slug IN ('core', 'customers', 'settings', 'users')
   AND NOT EXISTS (
       SELECT 1 FROM company_modules cm
        WHERE cm.company_id = c.id
          AND cm.module_id = mc.id
   );

-- 2) Forzar activo cualquier fila existente del subarbol core que este
--    en FALSE. Limpia las marcas de desactivacion para que la trazabilidad
--    de auditoria refleje el reset.
UPDATE company_modules cm
   JOIN module_catalog mc ON mc.id = cm.module_id
    SET cm.active = TRUE,
        cm.deactivated_at = NULL,
        cm.deactivated_by = NULL
  WHERE mc.slug IN ('core', 'customers', 'settings', 'users')
    AND cm.active = FALSE;
