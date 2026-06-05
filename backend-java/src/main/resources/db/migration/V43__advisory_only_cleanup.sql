-- ===========================================================================
-- V43__advisory_only_cleanup.sql
--
-- DUAL-SIDEBAR (2026-06-05): cierra la deuda menor del backlog.
--
-- Hasta este slice, ModuleAccessService no filtraba los modulos
-- advisory_only=TRUE por company_type. Resultado: una empresa CLIENT
-- podia tener activado el modulo "Asesoria" (u otros advisory_only)
-- en company_modules.active=TRUE, por error en pruebas o por seed.
--
-- Este script limpia esos vinculos huerfanos: para cada empresa que NO
-- sea INTERNAL/ADVISORY, marca como inactivo cualquier modulo
-- advisory_only que tuviera activo. NO borra la fila — la deja con
-- active=FALSE para conservar la traza de quien lo activo y cuando.
--
-- Idempotente: si se ejecuta dos veces no pasa nada porque la segunda
-- vez ya no hay filas que matcheen el WHERE.
-- ===========================================================================

UPDATE company_modules cm
  JOIN module_catalog m   ON m.id = cm.module_id
  JOIN companies      c   ON c.id = cm.company_id
   SET cm.active = FALSE,
       cm.deactivated_at = CURRENT_TIMESTAMP,
       cm.deactivated_by = NULL  -- system cleanup, sin usuario
 WHERE cm.active = TRUE
   AND m.advisory_only = TRUE
   AND c.company_type NOT IN ('INTERNAL', 'ADVISORY');
