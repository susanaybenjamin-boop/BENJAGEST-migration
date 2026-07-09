-- ===========================================================================
-- fix-duplicate-ss-autonomo.sql   (Opción A, Benjamin 2026-07-09)
--
-- La cuota de autónomo (SS) está DUPLICADA en la contabilidad:
--   · Juego RECURRENTE (plantilla "Cuota autónomo") — facturas de compra,
--     Debe 642 / Haber 4000015. Alimenta el modelo 130. SE QUEDA.
--   · Juego IMPORTADO de CONTENDO — asientos Debe 642 / Haber 476·4000003
--     (devengo) + Debe 476·4000003 / Haber 572 (pago). SE BORRA.
--
-- Efecto de borrar el ciclo importado:
--   · 642 (gasto SS) pasa de 3.709,00 a 1.854,50 (deja de estar duplicado).
--   · 476 y 4000003 quedan sin movimientos (solo los usaba este ciclo).
--   · El banco 572 SUBE 1.854,50 (se quitan los pagos importados) — se
--     compensa al MARCAR PAGADAS las 6 facturas recurrentes de SS desde la
--     app (Registrar pago → banco → fecha fin de mes). Hazlo justo después.
--
-- El 130 NO cambia (sigue contando la SS una vez, por las facturas).
--
-- Verificado: esos asientos NO tienen bank_movements ni learning_events.
-- Las cuentas 476/4000003 se usan EXCLUSIVAMENTE en este ciclo (las
-- recurrentes van por 4000015), así que el criterio de selección es preciso.
--
-- Uso (BD embebida del instalable, puerto 13307):
--   mariadb --ssl=0 -h 127.0.0.1 -P 13307 -u benjagest -pbenjagest benjagest \
--       < scripts/fix-duplicate-ss-autonomo.sql
-- ===========================================================================

SET @co = (SELECT company_id FROM purchase_invoices
            WHERE supplier_name LIKE 'AUTOMOVILES%LOREN%' LIMIT 1);

-- Asientos del ciclo IMPORTADO de la SS = los que tocan 476 ó 4000003.
CREATE TEMPORARY TABLE ss_dup_entries (id CHAR(36) PRIMARY KEY)
    ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO ss_dup_entries (id)
SELECT DISTINCT e.id
  FROM journal_entries e
  JOIN journal_entry_lines l ON l.journal_entry_id = e.id
  JOIN accounting_accounts a ON a.id = l.account_id
 WHERE e.company_id = @co
   AND a.code IN ('476','4000003')
   AND e.source_type IN ('HISTORICAL_IMPORT','DUE_DATE_PAYMENT');

-- REVISIÓN: qué se va a borrar (ejecuta esto primero y compruébalo).
SELECT e.entry_number, e.entry_date, e.source_type, e.concept
  FROM journal_entries e JOIN ss_dup_entries d ON d.id = e.id
 ORDER BY e.entry_date;

-- Borrado (líneas primero por la FK).
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM journal_entry_lines WHERE journal_entry_id IN (SELECT id FROM ss_dup_entries);
DELETE FROM journal_entries     WHERE id             IN (SELECT id FROM ss_dup_entries);
SET FOREIGN_KEY_CHECKS = 1;

DROP TEMPORARY TABLE ss_dup_entries;

-- VERIFICACIÓN: el gasto 642 del 1S debe quedar en 1.854,50 (una sola vez).
SELECT COALESCE(SUM(l.debit),0) AS gasto_642_1S
  FROM journal_entry_lines l
  JOIN journal_entries e ON e.id = l.journal_entry_id
  JOIN accounting_accounts a ON a.id = l.account_id
 WHERE e.company_id = @co AND a.code = '642' AND l.debit > 0
   AND YEAR(e.entry_date) = 2026 AND MONTH(e.entry_date) <= 6;
