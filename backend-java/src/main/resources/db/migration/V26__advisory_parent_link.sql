-- ===========================================================================
-- V26__advisory_parent_link.sql
--
-- Modelo asesoria ↔ cliente. Decision tomada (backlog pre-analisis
-- 2026-06-02): parent_company_id simple 1:N.
--
-- Una empresa INTERNAL (asesoria) gestiona N empresas CLIENT/
-- MANAGED_CLIENT. Una empresa cliente tiene UNA SOLA asesoria.
--
-- Por que 1:N y no N:M:
--   - 99% de los casos reales tienen una sola asesoria por cliente
--     en un momento dado (la sucesion de asesorias es historico, no
--     N:M concurrente).
--   - El caso del 1% (auditor externo + asesor interno + gestor
--     fiscal) se modela con permisos finos por usuario, no con
--     "varias asesorias dueñas".
--   - 1:N es trivial en SQL (1 columna) frente a tabla N:M (entidad
--     intermedia).
--
-- Si el modelo crece a N:M en el futuro, se anyade
-- `advisory_client_links` y se ignora parent_company_id. La
-- migracion es no-destructiva.
-- ===========================================================================

ALTER TABLE companies
    ADD COLUMN parent_company_id CHAR(36) NULL AFTER company_type,
    ADD CONSTRAINT fk_companies_parent FOREIGN KEY (parent_company_id) REFERENCES companies (id);

CREATE INDEX ix_companies_parent ON companies (parent_company_id);
