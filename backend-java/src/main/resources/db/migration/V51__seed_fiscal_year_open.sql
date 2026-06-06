-- ===========================================================================
-- V51__seed_fiscal_year_open.sql
--
-- Siembra un fiscal_year OPEN del año en curso (2026) para todas las
-- empresas que aún no lo tengan. Sin esto, los servicios
-- PurchaseJournalEntryService y SalesJournalEntryService NO generan
-- asiento contable automático al guardar una factura recibida o validar
-- una emitida — el método findOpenFiscalYearId devuelve null y la
-- factura se persiste sin journal_entry_id.
--
-- Por qué hacerlo aquí y no en code:
--   - Solo lo hace la primera vez por empresa (NOT EXISTS).
--   - Es contable: ningún sistema serio debería operar sin ejercicio
--     fiscal definido, así que tener uno OPEN por empresa es la
--     condición mínima para que el flujo "factura → asiento" funcione.
--   - Cuando llegue el cierre real, YEAR-CLOSE creará el del año
--     siguiente. Mientras tanto cubrimos 2026.
--
-- Si el operador quiere otro rango (p.ej. ejercicio partido), lo edita
-- desde Configuración → Contabilidad → Ejercicios o desde DBeaver.
-- ===========================================================================

INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
SELECT UUID(), c.id, 2026, '2026-01-01', '2026-12-31', 'OPEN'
  FROM companies c
 WHERE NOT EXISTS (
       SELECT 1 FROM fiscal_years f
        WHERE f.company_id = c.id
          AND f.year_number = 2026
 );
