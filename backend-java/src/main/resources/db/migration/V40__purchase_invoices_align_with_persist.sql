-- ===========================================================================
-- V40__purchase_invoices_align_with_persist.sql
--
-- FIX PURCHASES-PERSIST (2026-06-05): V39 fue no-op porque la tabla
-- purchase_invoices ya existía desde V2 con otro schema (supplier_id,
-- subtotal, vat_total, total, payment_status, etc). El código del
-- service usa nombres distintos (supplier_nif, base_amount,
-- vat_amount, total_amount, status…) y la SELECT fallaba en runtime
-- con "Unknown column 'supplier_nif'".
--
-- Esta migración añade las columnas que mi código nuevo necesita
-- SIN tocar las viejas — las dos pueden coexistir. Las columnas
-- viejas seguirán siendo válidas si en el futuro alguien las
-- rellena; las nuevas son las que rellena PurchaseInvoiceService.
--
-- Idempotente: cada ADD COLUMN se hace solo si la columna no existe
-- ya (information_schema guard + PREPARE dinámico). Mismo patrón que
-- V26 / V38. NO usa DELIMITER ni PROCEDURE porque Flyway no parsea
-- esa directiva del cliente MySQL CLI.
-- ===========================================================================

-- ----- COLUMNAS NUEVAS -----

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'supplier_nif');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN supplier_nif VARCHAR(40) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'base_amount');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN base_amount DECIMAL(15,2) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'vat_percent');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN vat_percent DECIMAL(5,2) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'vat_amount');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN vat_amount DECIMAL(15,2) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'total_amount');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN total_amount DECIMAL(15,2) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'document_sha256');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN document_sha256 CHAR(64) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'invoice_index_in_pdf');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN invoice_index_in_pdf INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'status');
SET @sql := IF(@c = 0,
    "ALTER TABLE purchase_invoices ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'POSTED'",
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'journal_entry_id');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN journal_entry_id CHAR(36) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'notes');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN notes TEXT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'uploaded_by_user_id');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN uploaded_by_user_id CHAR(36) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND COLUMN_NAME = 'uploaded_by_company_id');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN uploaded_by_company_id CHAR(36) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----- CONSTRAINTS / FKs / INDICES -----

-- UNIQUE de dedup (company, sha, index).
SET @c := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND CONSTRAINT_NAME = 'uk_purchase_invoices_dedup');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD CONSTRAINT uk_purchase_invoices_dedup
        UNIQUE (company_id, document_sha256, invoice_index_in_pdf)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- FK al asiento contable.
SET @c := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND CONSTRAINT_NAME = 'fk_purchase_invoices_journal_v40');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD CONSTRAINT fk_purchase_invoices_journal_v40
        FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- FK al usuario que subió.
SET @c := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND CONSTRAINT_NAME = 'fk_purchase_invoices_uploader_user_v40');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD CONSTRAINT fk_purchase_invoices_uploader_user_v40
        FOREIGN KEY (uploaded_by_user_id) REFERENCES user_accounts (id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- FK a la empresa del usuario que subió (puede ser asesoría).
SET @c := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_invoices'
              AND CONSTRAINT_NAME = 'fk_purchase_invoices_uploader_company_v40');
SET @sql := IF(@c = 0,
    'ALTER TABLE purchase_invoices ADD CONSTRAINT fk_purchase_invoices_uploader_company_v40
        FOREIGN KEY (uploaded_by_company_id) REFERENCES companies (id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
