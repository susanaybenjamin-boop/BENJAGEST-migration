-- =============================================================================
-- V82 — Registrar el módulo "employee-portal" (Portal del empleado) y
-- activarlo para todas las empresas existentes.
--
-- Contexto (PORT-1):
--   CONTENDO tenía app/empleado/* con 4 vistas (calendario, nóminas,
--   notificaciones, trabajos). BENJAGEST porta el mismo concepto como un
--   único módulo con 4 pestañas en la misma JavaFX (no app aparte —
--   decisión implícita: el rol EMPLOYEE ya entra en BENJAGEST con PIN).
--
-- Decisiones de modelado:
--   - advisory_only = FALSE → visible en CUALQUIER empresa (cliente o
--     asesoría). Una asesoría también tiene empleados.
--   - parent_id     = NULL  → módulo de raíz.
--   - display_order = 50    → arriba del todo, antes de Facturación (60)
--     porque para un empleado es su pantalla principal.
--   - icon          = fas-user-clock → reusa el patrón de fichaje
--     (relacionado conceptualmente).
--
-- Activación retroactiva: se enciende para TODAS las empresas (cualquier
-- tipo). Las nuevas lo heredarán de su flujo de alta o desde Configuración.
-- =============================================================================

INSERT INTO module_catalog
       (id, slug, label, description, parent_id, icon,
        display_order, advisory_only)
VALUES (UUID(), 'employee-portal', 'Portal del empleado',
        'Vistas personales del empleado: su calendario laboral, sus nóminas, sus notificaciones y sus trabajos asignados. Visible para cualquier rol pero pensado para EMPLOYEE.',
        NULL, 'fas-user-clock', 50, FALSE);

INSERT INTO company_modules (id, company_id, module_id, active,
                              activated_at, activated_by)
SELECT UUID(), c.id, m.id, TRUE, CURRENT_TIMESTAMP, NULL
  FROM companies c
  CROSS JOIN (SELECT id FROM module_catalog WHERE slug = 'employee-portal') m
ON DUPLICATE KEY UPDATE active = TRUE,
                        deactivated_at = NULL,
                        deactivated_by = NULL;
