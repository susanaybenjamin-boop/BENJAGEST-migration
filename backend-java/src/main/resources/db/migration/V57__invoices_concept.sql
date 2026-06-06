-- V57 — Concepto del gasto/ingreso para asientos contables
--
-- CONTENDO almacena un "concepto" en cada factura que se usa como
-- descripción de la línea contable del gasto/ingreso (cuenta 6xx/7xx)
-- — lo que en el extracto bancario o en el libro Mayor el asesor lee
-- para entender qué fue ese movimiento.
--
-- BENJAGEST no tenía ese campo, así que las líneas se generaban con un
-- "Fra. N - Proveedor" repetido en todas las líneas, lo que no es lo
-- que el asesor espera en el libro Mayor de la cuenta de gasto.
--
-- Reglas que aplican los services tras este cambio:
--   • Cuenta proveedor (4000xxx): description = nombre del proveedor
--   • Cuenta cliente   (4300xxx): description = nombre del cliente
--   • Cuenta IVA       (472/477): description = "IVA soportado XX%"
--                                              / "IVA repercutido XX%"
--   • Cuenta retención (473):     description = "Retención IRPF"
--   • Cuenta gasto     (6xx):     description = concept (si existe)
--                                              o nombre de cuenta de gasto
--   • Cuenta ingreso   (7xx):     description = concept (si existe)
--                                              o nombre de cuenta de ingreso
--
-- Idempotente.

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'purchase_invoices'
       AND column_name = 'concept'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE purchase_invoices ADD COLUMN concept VARCHAR(240) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'sales_invoices'
       AND column_name = 'concept'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE sales_invoices ADD COLUMN concept VARCHAR(240) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
