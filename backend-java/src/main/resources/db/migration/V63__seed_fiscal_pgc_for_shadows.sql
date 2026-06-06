-- V63 — Sembrar fiscal_year + plan contable a shadow companies existentes
--
-- Las shadow companies (parent_company_id != NULL, company_type='MANAGED_CLIENT')
-- creadas por el endpoint start-management ANTES de este fix se quedaron sin
-- fiscal_year OPEN y sin plan contable. Consecuencia:
--   • PurchaseJournalEntryService.createForPurchase devuelve null
--     (findOpenFiscalYearId no encuentra ejercicio).
--   • La factura se persiste sin journal_entry_id.
--   • La pestaña "Por validar" del Diario queda vacía aunque el asesor
--     haya importado 10 gastos.
--
-- Esta migración hace el backfill para las shadows existentes:
--   1) fiscal_year OPEN del año en curso si no lo tienen.
--   2) Cuentas estándar del plan contable copiadas desde la asesoría
--      madre (que ya las tenía sembradas por V46).
--
-- Las shadows nuevas a partir de este fix ya los reciben dentro de
-- AdvisoryService.ensureManagedCompany.

-- 1) fiscal_year 2026 OPEN para shadow companies sin él
INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
SELECT UUID(), c.id, 2026, '2026-01-01', '2026-12-31', 'OPEN'
  FROM companies c
 WHERE c.company_type = 'MANAGED_CLIENT'
   AND c.parent_company_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM fiscal_years f
        WHERE f.company_id = c.id
          AND f.year_number = 2026);

-- 2) Cuentas estándar copiadas de la asesoría madre
--    INSERT IGNORE evita duplicados si alguna existe ya.
INSERT IGNORE INTO accounting_accounts
    (id, company_id, code, name, account_type, active, is_standard)
SELECT UUID(), shadow.id, parent_acc.code, parent_acc.name,
       parent_acc.account_type, TRUE, TRUE
  FROM companies shadow
  JOIN accounting_accounts parent_acc
    ON parent_acc.company_id = shadow.parent_company_id
   AND parent_acc.is_standard = TRUE
   AND parent_acc.active = TRUE
 WHERE shadow.company_type = 'MANAGED_CLIENT'
   AND shadow.parent_company_id IS NOT NULL;
