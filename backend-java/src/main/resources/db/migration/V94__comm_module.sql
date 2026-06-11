-- =============================================================================
-- V94 — Módulo "comm" (Comunicación) — comunicación asesoría ↔ cliente
--
-- Antes los tabs Mensajes + Documentos vivían dentro de Configuración →
-- Mi asesoría (solo visible al empresario). Decisión Benjamin 2026-06-11:
-- sacarlos a un módulo propio del sidebar, mismo nombre en ambos modos,
-- para que el asesor también pueda acceder.
--
-- Características:
--   - advisory_only = FALSE → ambos modos lo ven en el sidebar.
--   - parent_id = NULL → módulo raíz.
--   - display_order = 145 → tras team (135).
--   - icon = fas-comments → mismo concepto de mensajería.
--
-- Activación retroactiva: para todas las empresas-tenant (parent_company_id
-- IS NULL). Las fichas MANAGED_CLIENT no entran porque no son tenants
-- (ver project_benjagest_managed_clients).
-- =============================================================================

INSERT INTO module_catalog
       (id, slug, label, description, parent_id, icon,
        display_order, advisory_only)
VALUES (UUID(), 'comm', 'Comunicación',
        'Comunicación asesoría ↔ cliente: mensajes y documentos compartidos en un solo módulo.',
        NULL, 'fas-comments', 145, FALSE);

INSERT INTO company_modules (id, company_id, module_id, active,
                              activated_at, activated_by)
SELECT UUID(), c.id, m.id, TRUE, CURRENT_TIMESTAMP, NULL
  FROM companies c
  CROSS JOIN (SELECT id FROM module_catalog WHERE slug = 'comm') m
 WHERE c.parent_company_id IS NULL
ON DUPLICATE KEY UPDATE active = TRUE,
                        deactivated_at = NULL,
                        deactivated_by = NULL;
