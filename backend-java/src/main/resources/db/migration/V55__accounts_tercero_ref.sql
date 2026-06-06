-- V55 — Sub-cuentas de tercero en accounting_accounts
--
-- Port de la lógica de CONTENDO (pgc_cuentas_180.tercero_ref + tercero_aliases)
-- para resolver O(1) la sub-cuenta de un cliente/proveedor por su nombre
-- normalizado (UPPER + TRIM). Al validar una factura de compra/venta:
--   • 4000xx = sub-cuenta proveedor (padre 400)
--   • 4300xx = sub-cuenta cliente   (padre 430)
--
-- Si la cuenta es estándar del PGC (400, 430, 600, 700…), is_standard = TRUE
-- y NO se considera tercero (no lleva tercero_ref). Si es sub-cuenta de
-- tercero, lleva tercero_ref con el nombre normalizado para búsqueda directa.
--
-- Idempotente: ADD COLUMN IF NOT EXISTS no existe en MariaDB <10.0; usamos
-- el patrón information_schema + dynamic SQL para que la migración sea
-- re-ejecutable sin error en entornos donde V55 ya corrió parcialmente.

-- 1. tercero_ref: nombre normalizado del tercero original (UPPER + TRIM).
SET @col_exists = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'accounting_accounts'
       AND column_name = 'tercero_ref'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE accounting_accounts ADD COLUMN tercero_ref VARCHAR(150) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. tercero_aliases: JSON array con nombres alternativos (acumulado tras merges).
SET @col_exists = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'accounting_accounts'
       AND column_name = 'tercero_aliases'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE accounting_accounts ADD COLUMN tercero_aliases JSON NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. is_standard: marca cuentas estándar del PGC (no se tocan al resolver tercero).
SET @col_exists = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'accounting_accounts'
       AND column_name = 'is_standard'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE accounting_accounts ADD COLUMN is_standard BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. tercero_type: 'cliente'|'proveedor'|null (null = cuenta no tercero).
SET @col_exists = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'accounting_accounts'
       AND column_name = 'tercero_type'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE accounting_accounts ADD COLUMN tercero_type VARCHAR(20) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. tercero_nif: NIF del tercero, búsqueda secundaria si el nombre cambia.
SET @col_exists = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'accounting_accounts'
       AND column_name = 'tercero_nif'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE accounting_accounts ADD COLUMN tercero_nif VARCHAR(20) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6. Backfill: marcar como estándar las cuentas con código corto (3 dígitos sueltos).
UPDATE accounting_accounts
   SET is_standard = TRUE
 WHERE LENGTH(code) <= 3
   AND is_standard = FALSE;

-- 7. Índices.
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'accounting_accounts'
       AND index_name = 'idx_acc_tercero_ref'
);
SET @sql = IF(@idx_exists = 0,
    'CREATE INDEX idx_acc_tercero_ref ON accounting_accounts (company_id, tercero_ref)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'accounting_accounts'
       AND index_name = 'idx_acc_tercero_nif'
);
SET @sql = IF(@idx_exists = 0,
    'CREATE INDEX idx_acc_tercero_nif ON accounting_accounts (company_id, tercero_nif)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
