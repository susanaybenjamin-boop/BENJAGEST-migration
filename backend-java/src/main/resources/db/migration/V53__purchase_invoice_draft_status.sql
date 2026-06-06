-- ===========================================================================
-- V53__purchase_invoice_draft_status.sql
--
-- Permitir status DRAFT en purchase_invoices. V40 había eliminado el
-- CHECK constraint original. Esta migración:
--   1) Elimina el CHECK actual si existe (cualquier nombre).
--   2) Añade CHECK que acepta DRAFT, POSTED y VOID.
--
-- Necesario para gastos RECURRENTES que se generan en borrador: el
-- sistema crea la factura del recurrente, el usuario la revisa (puede
-- ajustar precio, importes, añadir líneas) y la valida en lote
-- mediante multiselección.
-- ===========================================================================

-- Eliminar todos los CHECK constraints sobre `status` de purchase_invoices,
-- cualquiera que sea su nombre. Idempotente.
SET @cname := (SELECT constraint_name FROM information_schema.check_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'purchase_invoices'
                  AND check_clause LIKE '%status%'
                LIMIT 1);
SET @sql := IF(@cname IS NOT NULL,
    CONCAT('ALTER TABLE purchase_invoices DROP CONSTRAINT `', @cname, '`'),
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Añadir el constraint nuevo con DRAFT incluido.
ALTER TABLE purchase_invoices
    ADD CONSTRAINT ck_purchase_invoices_status_v53
    CHECK (status IN ('DRAFT', 'POSTED', 'VOID'));
