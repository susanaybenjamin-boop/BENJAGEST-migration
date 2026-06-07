-- =========================================================================
-- V65 — Ampliar el CHECK constraint de recurring_tasks.kind para incluir
--       los nuevos kinds del Slice 3S: ACCOUNTING_INCOME y ACCOUNTING_EXPENSE.
--
-- Contexto: V50 sembró el CHECK con los 5 kinds originales
-- (PURCHASE, SALES_INVOICE, JOURNAL_ENTRY, TEMPLATE_APPLY, LOAN_AUTO_PAY).
-- El Slice 3S añade dos kinds nuevos para shadow companies (clientes sin
-- vínculo desde asesoría) que generan asiento contable directo con metadata
-- fiscal completa (base/IVA/retención/tercero), sin pasar por la factura
-- legal — necesarios para que los modelos AEAT 303/347/190 cuadren.
--
-- MariaDB 11.4 no permite ALTER CONSTRAINT, así que toca DROP + ADD.
-- =========================================================================

ALTER TABLE recurring_tasks
    DROP CONSTRAINT IF EXISTS ck_rt_kind;

ALTER TABLE recurring_tasks
    ADD CONSTRAINT ck_rt_kind CHECK (kind IN (
        'PURCHASE', 'SALES_INVOICE', 'JOURNAL_ENTRY',
        'TEMPLATE_APPLY', 'LOAN_AUTO_PAY',
        'ACCOUNTING_INCOME', 'ACCOUNTING_EXPENSE'
    ));
