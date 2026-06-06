-- V62 — Re-activar módulos esenciales en TODAS las companies (incl. shadows)
--
-- V54 activó módulos para todas las companies existentes en ese momento.
-- Pero entre V54 y V62 se introdujo el endpoint start-management
-- (commit 277aace, V59), que crea SHADOW companies para clientes no
-- vinculados. Esas shadows nuevas se quedaban sin módulos hasta que
-- ensureManagedCompany se actualizara (commit posterior).
--
-- Esta migración hace de "red de seguridad": vuelve a aplicar el mismo
-- INSERT IGNORE + UPDATE de V54 para que cualquier shadow company creada
-- entre V54 y este fix tenga los módulos activos.
--
-- Idempotente: si ya tienen los módulos no toca nada; si están active=0
-- los pone a TRUE.

INSERT IGNORE INTO company_modules (id, company_id, module_id, active)
SELECT UUID(), c.id, m.id, TRUE
  FROM companies c
  CROSS JOIN module_catalog m
 WHERE m.slug IN ('core', 'billing', 'purchases', 'accounting', 'tax',
                  'calendar', 'notifications', 'labor', 'reports', 'settings')
   AND m.active_in_catalog = TRUE;

UPDATE company_modules cm
  JOIN module_catalog m ON m.id = cm.module_id
   SET cm.active = TRUE,
       cm.activated_at = CURRENT_TIMESTAMP,
       cm.deactivated_at = NULL
 WHERE m.slug IN ('core', 'billing', 'purchases', 'accounting', 'tax',
                  'calendar', 'notifications', 'labor', 'reports', 'settings')
   AND cm.active = FALSE;
