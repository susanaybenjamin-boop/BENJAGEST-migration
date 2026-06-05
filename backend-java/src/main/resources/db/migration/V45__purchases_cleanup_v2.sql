-- ===========================================================================
-- V45__purchases_cleanup_v2.sql
--
-- PURCHASES-CLEANUP-V2 (2026-06-05): elimina las columnas y tablas
-- legacy del bloque de compras que arrastrábamos desde V2.
--
-- Histórico:
--   V2  creó `purchase_invoices` con un schema generico (supplier_id,
--       category, subtotal, vat_total, total, payment_status,
--       document_hash, document_file_id, active) + tablas hermanas
--       `purchase_invoice_lines` y `recurring_expenses`.
--   V39 introdujo `purchase_invoices` real (slice PDF→persist) pero
--       como V2 ya había creado la tabla, CREATE TABLE IF NOT EXISTS
--       fue NO-OP.
--   V40 añadió ALTER TABLE idempotentes para meter las columnas
--       nuevas (supplier_nif, base_amount, vat_percent, vat_amount,
--       total_amount, document_sha256, invoice_index_in_pdf, status,
--       journal_entry_id, notes, uploaded_by_*) junto a las viejas.
--   PURCHASES-CLEANUP (slice anterior) decidió DELETE físico y
--       renombró la pantalla.
--
-- Hoy las viejas YA NO LAS USA NADIE: el único caller activo era
-- WorkspaceRepository.createPurchase/updatePurchase del dashboard
-- genérico, eliminados en este mismo slice. El listado del dashboard
-- (`purchases()`) y los KPI se refactoran a las columnas nuevas en el
-- mismo commit.
--
-- Esta migración:
--   1) Backfill defensivo: para cada fila donde las columnas nuevas
--      estén NULL pero las viejas tengan valor, copia. No revierte
--      en sentido contrario (la nueva manda si está rellena).
--   2) DROP COLUMN obsoletas — idempotente vía information_schema.
--   3) DROP TABLE purchase_invoice_lines y recurring_expenses si
--      siguen vacías o inactivas. Nadie las usa fuera del código que
--      acabo de retirar.
--
-- Si en el futuro vuelve a hacer falta "líneas de detalle" en una
-- compra (probable cuando se ataquen los modelos AEAT 347), se
-- creará una tabla nueva con schema correcto, no recuperando esta.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) Backfill: copiar valores viejos → nuevos donde falte
-- ---------------------------------------------------------------------------
UPDATE purchase_invoices SET total_amount = total
 WHERE total_amount IS NULL AND total IS NOT NULL;

UPDATE purchase_invoices SET base_amount = subtotal
 WHERE base_amount IS NULL AND subtotal IS NOT NULL;

UPDATE purchase_invoices SET vat_amount = vat_total
 WHERE vat_amount IS NULL AND vat_total IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2a) DROP FOREIGN KEY antes de dropear las columnas FK.
--     MariaDB no permite DROP COLUMN de una columna con FK; hay que
--     soltar primero la constraint. Idempotente vía
--     information_schema.TABLE_CONSTRAINTS.
-- ---------------------------------------------------------------------------
SET @fk := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
             WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
               AND CONSTRAINT_NAME = 'fk_purchase_invoices_supplier'
               AND CONSTRAINT_TYPE = 'FOREIGN KEY');
SET @s := IF(@fk > 0, 'ALTER TABLE purchase_invoices DROP FOREIGN KEY fk_purchase_invoices_supplier', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
             WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
               AND CONSTRAINT_NAME = 'fk_purchase_invoices_document'
               AND CONSTRAINT_TYPE = 'FOREIGN KEY');
SET @s := IF(@fk > 0, 'ALTER TABLE purchase_invoices DROP FOREIGN KEY fk_purchase_invoices_document', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2b) DROP COLUMN obsoletas — idempotente por information_schema
-- ---------------------------------------------------------------------------

-- Macro local: si la columna existe, ALTER TABLE DROP COLUMN; si no, SELECT 1.
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'supplier_id');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN supplier_id', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'category');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN category', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'subtotal');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN subtotal', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'vat_total');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN vat_total', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'retention_total');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN retention_total', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'total');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN total', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'payment_status');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN payment_status', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'document_hash');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN document_hash', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'document_file_id');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN document_file_id', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'active');
SET @s := IF(@c > 0, 'ALTER TABLE purchase_invoices DROP COLUMN active', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3) DROP TABLES obsoletas — idempotente.
--    purchase_invoice_lines no se ha usado nunca con datos reales: V2
--    la creó pero el flujo nuevo guarda monto agregado, no líneas.
--    recurring_expenses idem; el slice de gastos recurrentes está en
--    backlog pero, cuando llegue, se hará con schema propio.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS purchase_invoice_lines;
DROP TABLE IF EXISTS recurring_expenses;
