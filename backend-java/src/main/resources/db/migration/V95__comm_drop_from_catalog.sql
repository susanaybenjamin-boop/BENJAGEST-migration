-- =============================================================================
-- V95 — Quitar "comm" del catálogo de módulos
--
-- Decisión Benjamin 2026-06-11 noche: el módulo Comunicación NO debe
-- ser seleccionable desde Configuración → Módulos. Solo tiene sentido
-- cuando hay una vinculación real entre asesoría y empresario
-- (CLIENT.parent_company_id apuntando a INTERNAL). En ese caso aparece
-- automáticamente en el sidebar; sin vínculo no aparece.
--
-- ModuleAccessService inyecta dinámicamente un Module virtual con
-- slug=comm cuando detecta vínculo, así no necesita estar en
-- module_catalog.
--
-- Antes: V94 registraba comm + activaba por defecto. Esta V95 revierte.
-- =============================================================================

DELETE FROM company_modules
 WHERE module_id IN (SELECT id FROM module_catalog WHERE slug = 'comm');

DELETE FROM module_catalog WHERE slug = 'comm';
