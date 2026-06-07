-- =============================================================================
-- V69 — Registrar el módulo "team" (EQUIPO / Reparto de clientes) en el
-- catálogo de módulos y activarlo para todas las asesorías existentes.
--
-- Contexto:
--   El sidebar del frontend consume /api/modules-catalog/active, que cruza
--   module_catalog × company_modules. Si "team" no está aquí, la pantalla
--   nunca aparece en el sidebar aunque el código Java esté.
--
-- Decisiones de modelado:
--   - advisory_only = TRUE  → solo se ofrece a INTERNAL/ADVISORY (V43
--     limpia la fila si por error se activara en un CLIENT).
--   - parent_id     = NULL  → módulo de raíz, no submódulo. Hace tope con
--     "advisory" y "kiosk" en la categoría de raíz "asesoría".
--   - display_order = 135   → entre 130 ("advisory") y 140 ("kiosk").
--   - icon          = fas-users-cog → coincide con el icono que la UI
--     ya usa en ADVISORY_MODULES (consistencia visual).
--
-- Activación retroactiva:
--   El módulo se enciende automáticamente para todas las empresas
--   INTERNAL/ADVISORY existentes (las únicas que pueden usarlo). Las
--   asesorías creadas a partir de ahora deberían activarlo en su flujo
--   de alta — eso lo cierra un slice ALTA posterior si hace falta;
--   mientras tanto, una asesoría nueva puede activarlo desde
--   Configuración → Módulos.
-- =============================================================================

INSERT INTO module_catalog
       (id, slug, label, description, parent_id, icon,
        display_order, advisory_only)
VALUES (UUID(), 'team', 'Equipo',
        'Reparto de clientes entre los empleados de la asesoría, con módulos por asignación y delegación temporal por bajas/vacaciones.',
        NULL, 'fas-users-cog', 135, TRUE);

-- Activar el módulo para todas las INTERNAL/ADVISORY existentes
-- (idempotente: si la fila ya existiera, ON DUPLICATE KEY UPDATE la
-- reactiva sin tocar activated_at original).
INSERT INTO company_modules (id, company_id, module_id, active,
                              activated_at, activated_by)
SELECT UUID(), c.id, m.id, TRUE, CURRENT_TIMESTAMP, NULL
  FROM companies c
  CROSS JOIN (SELECT id FROM module_catalog WHERE slug = 'team') m
 WHERE c.company_type IN ('INTERNAL', 'ADVISORY')
ON DUPLICATE KEY UPDATE active = TRUE,
                        deactivated_at = NULL,
                        deactivated_by = NULL;
