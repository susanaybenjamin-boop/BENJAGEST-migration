-- ===========================================================================
-- repair-vehicle-accounts.sql
--
-- Bloque IRPF-DED (Benjamin 2026-07-09). Reclasifica los gastos de VEHÍCULO
-- ya existentes a sus subcuentas (creadas por la migración V173), para que
-- dejen de contar como deducibles en IRPF (modelo 130):
--
--   · Reparación furgoneta (AUTOMOVILES Y TALLERES LOREN)  622 → 6221
--   · Combustible (SOLRED)                                 628 → 6281
--
-- Qué hace por cada gasto:
--   1. Repunta la línea 6xx del asiento a la subcuenta de vehículo.
--   2. Fija expense_account_code y expense_deductible = 0 en la factura.
--
-- SEGURO E IDEMPOTENTE:
--   · Si las subcuentas 6221/6281 aún no existen (V173 no aplicada), los
--     JOIN no encajan y NO se toca nada (no-op). Aplica V173 primero
--     (reconstruyendo el MSI) y luego ejecuta este script.
--   · Si se ejecuta dos veces, la segunda no encuentra líneas en 622/628 de
--     esos proveedores (ya están en las subcuentas) → no-op.
--   · NO toca el SIF ni la cadena Veri*Factu: reclasificar la cuenta de un
--     gasto es contabilidad normal, no un evento del registro inalterable.
--
-- Uso (BD embebida del instalable, puerto 13307):
--   mariadb --ssl=0 -h 127.0.0.1 -P 13307 -u benjagest -pbenjagest benjagest \
--       < scripts/repair-vehicle-accounts.sql
-- ===========================================================================

-- --- 1) Furgoneta: reparación 622 → 6221 (no deducible IRPF) ----------------
UPDATE journal_entry_lines l
  JOIN journal_entries je      ON je.id = l.journal_entry_id
                              AND je.source_type = 'PURCHASE_INVOICE'
  JOIN purchase_invoices p     ON p.id = je.source_id
  JOIN accounting_accounts cur ON cur.id = l.account_id AND cur.code = '622'
  JOIN accounting_accounts sub ON sub.company_id = p.company_id AND sub.code = '6221'
   SET l.account_id = sub.id
 WHERE p.supplier_name LIKE 'AUTOMOVILES%LOREN%'
   AND l.debit > 0;

UPDATE purchase_invoices p
  JOIN accounting_accounts sub ON sub.company_id = p.company_id AND sub.code = '6221'
   SET p.expense_account_code = '6221',
       p.expense_deductible   = 0
 WHERE p.supplier_name LIKE 'AUTOMOVILES%LOREN%';

-- --- 2) Combustible: SOLRED 628 → 6281 (no deducible IRPF) ------------------
UPDATE journal_entry_lines l
  JOIN journal_entries je      ON je.id = l.journal_entry_id
                              AND je.source_type = 'PURCHASE_INVOICE'
  JOIN purchase_invoices p     ON p.id = je.source_id
  JOIN accounting_accounts cur ON cur.id = l.account_id AND cur.code = '628'
  JOIN accounting_accounts sub ON sub.company_id = p.company_id AND sub.code = '6281'
   SET l.account_id = sub.id
 WHERE p.supplier_name LIKE 'SOLRED%'
   AND l.debit > 0;

UPDATE purchase_invoices p
  JOIN accounting_accounts sub ON sub.company_id = p.company_id AND sub.code = '6281'
   SET p.expense_account_code = '6281',
       p.expense_deductible   = 0
 WHERE p.supplier_name LIKE 'SOLRED%';

-- --- 3) Verificación: gasto deducible IRPF del 1T tras la reparación --------
--   (compáralo con la casilla de gastos de tu 130 real del 1T)
SELECT 'Gasto deducible IRPF 1T (tras reparar)' AS concepto,
       COALESCE(SUM(base_amount), 0)            AS total
  FROM purchase_invoices
 WHERE YEAR(invoice_date) = 2026
   AND MONTH(invoice_date) <= 3
   AND expense_deductible = 1;
