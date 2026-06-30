-- V159 — Back-fill del tipo de IVA (vat_rate) en los asientos existentes.
--
-- V158 añadió la columna; los asientos creados ANTES quedaron con vat_rate
-- NULL. Como el 303/390 se construyen ahora desde los asientos por tipo, esos
-- asientos antiguos no aparecerían hasta etiquetarlos. Esta migración rellena
-- vat_rate (solo donde está NULL) derivándolo de la factura origen.
--
--   • COMPRAS: un único tipo por factura (cabecera) → se etiquetan las líneas
--     de gasto (6xx) e IVA soportado (472) con purchase_invoices.vat_percent.
--   • VENTAS de UN SOLO tipo: se etiquetan las líneas de ingreso (7xx) e IVA
--     repercutido (477) con el tipo derivado (vat_total / subtotal).
--   • VENTAS con VARIOS tipos: NO se tocan aquí (requieren dividir las líneas
--     por tipo — eso lo hace "Regenerar asiento" en backend, factura a factura).
--
-- Aditiva e idempotente: solo escribe donde vat_rate IS NULL.

-- 1) Compras: 6xx (base) + 472 (IVA soportado).
UPDATE journal_entry_lines l
  JOIN journal_entries e ON e.id = l.journal_entry_id
                        AND e.source_type = 'PURCHASE_INVOICE'
  JOIN purchase_invoices p ON p.id = e.source_id
  JOIN accounting_accounts a ON a.id = l.account_id
   SET l.vat_rate = p.vat_percent
 WHERE l.vat_rate IS NULL
   AND p.vat_percent IS NOT NULL
   AND (a.code LIKE '472%' OR a.code LIKE '6%');

-- 2) Ventas de un solo tipo: 7xx (base) + 477 (IVA repercutido).
UPDATE journal_entry_lines l
  JOIN journal_entries e ON e.id = l.journal_entry_id
                        AND e.source_type = 'SALES_INVOICE'
  JOIN sales_invoices s ON s.id = e.source_id
  JOIN accounting_accounts a ON a.id = l.account_id
   SET l.vat_rate = ROUND(s.vat_total / NULLIF(s.subtotal, 0) * 100)
 WHERE l.vat_rate IS NULL
   AND s.subtotal > 0 AND s.vat_total > 0
   AND (a.code LIKE '477%' OR a.code LIKE '7%')
   AND (SELECT COUNT(DISTINCT sl.vat_percent)
          FROM sales_invoice_lines sl
         WHERE sl.invoice_id = s.id) = 1;
