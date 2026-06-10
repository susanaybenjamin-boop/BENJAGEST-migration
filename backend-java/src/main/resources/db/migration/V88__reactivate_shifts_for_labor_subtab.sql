-- =============================================================================
-- V88 — Reactivar el modulo "shifts" pero como uso interno de la sub-pestaña
-- de Labor (NO en el sidebar). Lo necesitamos para que @RequiresModule("shifts")
-- siga validando el acceso a /api/work-logs en empresas que activen Labor.
--
-- Decision Benjamin 2026-06-10 tarde: "jornadas y partes... debe anadirse al
-- modulo fichaje". Por lo tanto:
--   - El modulo "shifts" se REINSERTA en module_catalog (V87 lo habia
--     eliminado pensando que iba como modulo de raiz).
--   - Se activa AUTOMATICAMENTE en cualquier empresa que tenga "labor"
--     activo (la pestaña aparece bajo Labor).
--   - El sidebar NO lo muestra como modulo de raiz porque "shifts" no
--     esta en KNOWN_VIEWS (filtro del front).
-- =============================================================================

INSERT INTO module_catalog
       (id, slug, label, description, parent_id, icon,
        display_order, advisory_only)
VALUES (UUID(), 'shifts', 'Jornadas y partes',
        'Partes de día y jornadas como sub-modulo de Labor. Modelo embebido: un parte facturable puede generar línea de factura al cobrar.',
        NULL, 'fas-business-time', 145, FALSE)
ON DUPLICATE KEY UPDATE label = VALUES(label),
                        description = VALUES(description);

-- Activar shifts en cualquier empresa que tenga labor ACTIVO. Si la
-- empresa apaga labor, este modulo tambien se "apaga" desde el front
-- aunque permanezca active=TRUE en BD (acceso lo limita el front).
INSERT INTO company_modules (id, company_id, module_id, active,
                              activated_at, activated_by)
SELECT UUID(), cm.company_id, m.id, TRUE, CURRENT_TIMESTAMP, NULL
  FROM company_modules cm
  JOIN module_catalog labor ON labor.id = cm.module_id AND labor.slug = 'labor'
  CROSS JOIN (SELECT id FROM module_catalog WHERE slug = 'shifts') m
 WHERE cm.active = TRUE
ON DUPLICATE KEY UPDATE company_modules.active = TRUE,
                        deactivated_at = NULL,
                        deactivated_by = NULL;
