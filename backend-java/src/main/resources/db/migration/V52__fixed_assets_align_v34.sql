-- ===========================================================================
-- V52__fixed_assets_align_v34.sql
--
-- Bug histórico: V2 creó fixed_assets con un schema antiguo (sin code,
-- category, useful_life_years, residual_value, active). V34 intentó
-- recrearla con CREATE TABLE IF NOT EXISTS, lo que solo crea si NO
-- existe — como ya existía, la nueva definición se ignoró.
--
-- Resultado: el FixedAssetController hace SELECT id, code, ... y peta
-- con "Unknown column 'code'".
--
-- Esta migración añade idempotentemente las columnas que faltan y
-- backfilea code = 'FA' + secuencia para los registros existentes.
-- ===========================================================================

-- code VARCHAR(40) — clave humana del activo
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'code');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN code VARCHAR(40) NULL AFTER company_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- description TEXT
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'description');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN description TEXT NULL AFTER name',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- category VARCHAR(40) NOT NULL DEFAULT 'OTHER'
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'category');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN category VARCHAR(40) NOT NULL DEFAULT ''OTHER''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- accounting_account_id CHAR(36)
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'accounting_account_id');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN accounting_account_id CHAR(36) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- acquisition_cost — V34 usa este nombre, V2 usaba acquisition_value.
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'acquisition_cost');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN acquisition_cost DECIMAL(14,2) NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill acquisition_cost desde acquisition_value si existe la antigua.
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'acquisition_value');
SET @sql := IF(@col > 0,
    'UPDATE fixed_assets SET acquisition_cost = acquisition_value WHERE acquisition_cost = 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- residual_value
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'residual_value');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN residual_value DECIMAL(14,2) NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- useful_life_years DECIMAL(5,2)
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'useful_life_years');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN useful_life_years DECIMAL(5,2) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill useful_life_years desde useful_life_months si existe.
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'useful_life_months');
SET @sql := IF(@col > 0,
    'UPDATE fixed_assets SET useful_life_years = ROUND(useful_life_months / 12.0, 2) WHERE useful_life_years IS NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- in_service_date DATE
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'in_service_date');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN in_service_date DATE NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disposed_at DATE
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'disposed_at');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN disposed_at DATE NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disposal_reason VARCHAR(120)
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'disposal_reason');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN disposal_reason VARCHAR(120) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disposal_value DECIMAL(14,2)
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'disposal_value');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN disposal_value DECIMAL(14,2) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- supplier_name VARCHAR(200)
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'supplier_name');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN supplier_name VARCHAR(200) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- invoice_reference VARCHAR(120)
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'invoice_reference');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN invoice_reference VARCHAR(120) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- notes TEXT
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'notes');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN notes TEXT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- active BOOLEAN
SET @col := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'fixed_assets' AND column_name = 'active');
SET @sql := IF(@col = 0,
    'ALTER TABLE fixed_assets ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill code para registros existentes con NULL.
UPDATE fixed_assets
   SET code = CONCAT('FA-', LPAD(CRC32(id) % 100000, 5, '0'))
 WHERE code IS NULL OR code = '';

-- UNIQUE (company_id, code)
SET @uk := (SELECT COUNT(*) FROM information_schema.table_constraints
             WHERE table_schema = DATABASE()
               AND table_name = 'fixed_assets'
               AND constraint_name = 'uk_fixed_assets_code');
SET @sql := IF(@uk = 0,
    'ALTER TABLE fixed_assets ADD CONSTRAINT uk_fixed_assets_code UNIQUE (company_id, code)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
