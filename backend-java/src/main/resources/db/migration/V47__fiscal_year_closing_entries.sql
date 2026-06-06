-- ===========================================================================
-- V47__fiscal_year_closing_entries.sql
--
-- Añade el enlace fiscal_year_closes → asientos contables generados por el
-- nuevo ClosingEntriesService:
--
--   - regularization_entry_id: asiento que lleva los saldos de los grupos
--     6 (gastos) y 7 (ingresos) a la cuenta 129 (Resultado del ejercicio).
--   - closing_entry_id: asiento que pone TODAS las cuentas a saldo 0
--     contra sí mismas. Tras este asiento la balanza es cero.
--
-- Decisiones honestas:
--   - El asiento de APERTURA del año siguiente se generará en un sub-slice
--     posterior, cuando la UI permita transicionar a cerrar definitivamente
--     un ejercicio (estado SEALED). Por ahora la apertura queda como hueco
--     técnico — el operador puede crear el asiento inverso manualmente.
--   - La APLICACIÓN del resultado (129 → 113/526/121) sigue siendo manual
--     desde fiscal_year_closes (reserves/dividends/losses_allocation),
--     pero los asientos automáticos llevan el saldo a 129 que es lo que
--     consume el modelo 200.
--   - Las columnas son NULL: si el servicio aún no ha corrido, las
--     facturas siguen apareciendo sin asiento de regularización.
--
-- Idempotente vía information_schema. Funciona si V46 ya está aplicada.
-- ===========================================================================

SET @col_reg := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE()
                    AND table_name = 'fiscal_year_closes'
                    AND column_name = 'regularization_entry_id');
SET @sql_reg := IF(@col_reg = 0,
    'ALTER TABLE fiscal_year_closes ADD COLUMN regularization_entry_id CHAR(36) NULL AFTER notes',
    'SELECT 1');
PREPARE stmt FROM @sql_reg; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_clo := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE()
                    AND table_name = 'fiscal_year_closes'
                    AND column_name = 'closing_entry_id');
SET @sql_clo := IF(@col_clo = 0,
    'ALTER TABLE fiscal_year_closes ADD COLUMN closing_entry_id CHAR(36) NULL AFTER regularization_entry_id',
    'SELECT 1');
PREPARE stmt FROM @sql_clo; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- FK opcionales: si journal_entries.id existe, encadenamos.
SET @fk_reg := (SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE table_schema = DATABASE()
                   AND table_name = 'fiscal_year_closes'
                   AND constraint_name = 'fk_fyc_regularization_entry');
SET @sql_fk_reg := IF(@fk_reg = 0,
    'ALTER TABLE fiscal_year_closes
       ADD CONSTRAINT fk_fyc_regularization_entry
       FOREIGN KEY (regularization_entry_id) REFERENCES journal_entries (id)',
    'SELECT 1');
PREPARE stmt FROM @sql_fk_reg; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_clo := (SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE table_schema = DATABASE()
                   AND table_name = 'fiscal_year_closes'
                   AND constraint_name = 'fk_fyc_closing_entry');
SET @sql_fk_clo := IF(@fk_clo = 0,
    'ALTER TABLE fiscal_year_closes
       ADD CONSTRAINT fk_fyc_closing_entry
       FOREIGN KEY (closing_entry_id) REFERENCES journal_entries (id)',
    'SELECT 1');
PREPARE stmt FROM @sql_fk_clo; EXECUTE stmt; DEALLOCATE PREPARE stmt;
