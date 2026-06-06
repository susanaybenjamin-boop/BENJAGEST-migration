-- V59 — UK companies.tax_identifier relajada a (tax_identifier, parent_company_id)
--
-- Razón: la asesoría debe poder gestionar la contabilidad de un cliente
-- AUNQUE no esté vinculado. Para eso necesita una "shadow company" con
-- el NIF del cliente pero gestionada por la asesoría. Cuando más tarde
-- ese cliente acepte una invitación, su propia company (creada en su
-- alta) también lleva el mismo NIF — y la UK actual lo bloqueaba.
--
-- La nueva UK permite:
--   • company del empresario:   tax_identifier=NIF, parent_company_id=NULL
--   • shadow de la asesoría:    tax_identifier=NIF, parent_company_id=ASESORÍA
--
-- Ambas coexisten sin conflicto. Cuando el empresario acepte invitación,
-- la fusión de la shadow con su company queda para un slice futuro (de
-- momento la asesoría tendrá dos vistas del cliente — la suya y la del
-- empresario — y deberá migrar manualmente).
--
-- Idempotente: comprueba existencia antes de DROP/ADD.

SET @drop_uk = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'companies'
       AND index_name = 'uk_companies_tax_identifier'
);
SET @sql = IF(@drop_uk > 0,
    'ALTER TABLE companies DROP INDEX uk_companies_tax_identifier',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_uk = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'companies'
       AND index_name = 'uk_companies_tax_parent'
);
SET @sql = IF(@add_uk = 0,
    'ALTER TABLE companies ADD CONSTRAINT uk_companies_tax_parent UNIQUE (tax_identifier, parent_company_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
