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

-- Idempotente (fix 2026-06-04): MariaDB hace DDL no transaccional, por
-- lo que un ALTER con varias clausulas puede aplicar la primera y
-- fallar en la segunda, dejando el esquema medio-migrado. Si entonces
-- el repair() limpia la entrada FAILED, el segundo intento del ALTER
-- falla con "Duplicate column". Lo evitamos con IF NOT EXISTS para
-- la columna y comprobacion via information_schema para el FK.

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS parent_company_id CHAR(36) NULL AFTER company_type;

SET @fk_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'companies'
       AND constraint_name = 'fk_companies_parent');
SET @sql_fk = IF(@fk_exists = 0,
    'ALTER TABLE companies ADD CONSTRAINT fk_companies_parent FOREIGN KEY (parent_company_id) REFERENCES companies (id)',
    'SELECT 1');
PREPARE stmt_fk FROM @sql_fk;
EXECUTE stmt_fk;
DEALLOCATE PREPARE stmt_fk;

-- MariaDB 10.5+ soporta CREATE INDEX IF NOT EXISTS, pero por seguridad
-- usamos el patron information_schema tambien aqui.
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'companies'
       AND index_name = 'ix_companies_parent');
SET @sql_idx = IF(@idx_exists = 0,
    'CREATE INDEX ix_companies_parent ON companies (parent_company_id)',
    'SELECT 1');
PREPARE stmt_idx FROM @sql_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
