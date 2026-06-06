-- V56 — Configuración por empresa de la sub-cuenta de tercero
--
-- Decisión del 2026-06-06: el asesor quiere poder configurar por empresa:
--   • longitud total del código de tercero (ej. 4000 + 3 dígitos = 7,
--     o 4000 + 8 dígitos = 12). Rango admitido: 6 a 12.
--   • modo de generación del sufijo:
--       SEQUENTIAL → 1, 2, 3, … (padded a longitud-prefijo).
--       BY_NIF     → dígitos del NIF/CIF del tercero (solo dígitos,
--                    padded con ceros a la izquierda hasta longitud-prefijo).
--
-- Reglas de combinación:
--   • BY_NIF + NIF excede longitud → se amplía esa cuenta concreta
--     (la siguiente vuelve a respetar la config). Asegura unicidad.
--   • BY_NIF + sin NIF → fallback a SEQUENTIAL.
--   • BY_NIF + letras del NIF → se eliminan (solo dígitos).
--
-- Idempotente: ADD COLUMN protegido por information_schema.

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'companies'
       AND column_name = 'tercero_account_length'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE companies ADD COLUMN tercero_account_length INT NOT NULL DEFAULT 7',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'companies'
       AND column_name = 'tercero_account_mode'
);
SET @sql = IF(@col_exists = 0,
    "ALTER TABLE companies ADD COLUMN tercero_account_mode VARCHAR(20) NOT NULL DEFAULT 'SEQUENTIAL'",
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Check constraint (MariaDB acepta CHECK desde 10.2). Idempotente: ignora
-- el error si ya existe.
SET @cons_exists = (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'companies'
       AND constraint_name = 'ck_companies_tercero_length'
);
SET @sql = IF(@cons_exists = 0,
    'ALTER TABLE companies ADD CONSTRAINT ck_companies_tercero_length CHECK (tercero_account_length BETWEEN 6 AND 12)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @cons_exists = (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'companies'
       AND constraint_name = 'ck_companies_tercero_mode'
);
SET @sql = IF(@cons_exists = 0,
    "ALTER TABLE companies ADD CONSTRAINT ck_companies_tercero_mode CHECK (tercero_account_mode IN ('SEQUENTIAL', 'BY_NIF'))",
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
