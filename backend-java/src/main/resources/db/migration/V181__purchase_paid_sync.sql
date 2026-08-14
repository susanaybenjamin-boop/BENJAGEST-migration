-- PAGO-1 — Reparación de datos: gastos pagados por VENCIMIENTOS que seguían
-- marcados como NO pagados.
--
-- Había dos verdades sobre si un gasto está pagado y nadie las sincronizaba:
--   · purchase_invoices.paid          <- lo escribe "Registrar pago" (GAS-2, V167)
--   · invoice_due_dates.status='PAID' <- lo escribe "Vencimientos / Pago" (PV-1, V133)
--
-- Pagar por el segundo camino NUNCA tocaba el flag, así que el gasto quedaba
-- como "pendiente de pago" para siempre en cualquier listado. A partir de
-- ahora el código proyecta uno sobre otro (PaymentScheduleService), pero el
-- histórico ya escrito hay que arreglarlo aquí.
--
-- Criterio ESTRICTO y conservador: solo se marca pagado el gasto que TIENE
-- vencimientos y los tiene TODOS en PAID. Nunca al revés (jamás se desmarca
-- un gasto ya pagado): si el flag dice pagado, se respeta.
--
-- Additive: no crea ni borra nada, no toca seeds.

UPDATE purchase_invoices p
   SET p.paid = TRUE,
       p.paid_date = COALESCE(p.paid_date, (
           SELECT MAX(d.paid_date) FROM invoice_due_dates d
            WHERE d.company_id = p.company_id
              AND d.invoice_kind = 'PURCHASE'
              AND d.invoice_id = p.id)),
       p.payment_account_code = COALESCE(p.payment_account_code, (
           SELECT MAX(d.treasury_account_code) FROM invoice_due_dates d
            WHERE d.company_id = p.company_id
              AND d.invoice_kind = 'PURCHASE'
              AND d.invoice_id = p.id))
 WHERE p.paid = FALSE
   AND EXISTS (
       SELECT 1 FROM invoice_due_dates d
        WHERE d.company_id = p.company_id
          AND d.invoice_kind = 'PURCHASE'
          AND d.invoice_id = p.id)
   AND NOT EXISTS (
       SELECT 1 FROM invoice_due_dates d
        WHERE d.company_id = p.company_id
          AND d.invoice_kind = 'PURCHASE'
          AND d.invoice_id = p.id
          AND d.status <> 'PAID');
