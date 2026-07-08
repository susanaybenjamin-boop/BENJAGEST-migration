-- V171 (bloque IVA-COMP / migración, 2026-07-09): etiquetar el tipo de IVA
-- (vat_rate) en las líneas de asiento de facturas IMPORTADAS cuyo asiento se
-- creó SIN etiquetar (migración desde CONTENDO/A3/Sage/etc.).
--
-- Problema: el 303 saca las bases de IVA de las líneas 7xx/6xx del Diario
-- etiquetadas con su vat_rate. Los asientos migrados venían sin etiquetar
-- (vat_rate NULL), así que el 303 no veía las ventas/gastos importados y salía
-- a cero. Las FACTURAS sí llevan el tipo (sales_invoice_lines.vat_percent /
-- purchase_invoices.vat_percent), así que lo copiamos al asiento.
--
-- Solo toca líneas SIN etiquetar (idempotente). Ventas: solo facturas de tipo
-- de IVA ÚNICO (las de varios tipos habría que desglosarlas y no se puede a
-- posteriori con una sola línea). Compras: el vat_percent de cabecera (la
-- tolerancia del 303, ±1, absorbe el ruido de redondeo tipo 20,98/21,01).

UPDATE journal_entry_lines l
  JOIN journal_entries e ON e.id = l.journal_entry_id AND e.source_type = 'SALES_INVOICE'
  JOIN accounting_accounts a ON a.id = l.account_id
  JOIN (
      SELECT invoice_id, MAX(vat_percent) rate, COUNT(DISTINCT vat_percent) nrates
        FROM sales_invoice_lines GROUP BY invoice_id
  ) r ON r.invoice_id = e.source_id
   SET l.vat_rate = r.rate
 WHERE l.vat_rate IS NULL
   AND r.nrates = 1
   AND (a.code LIKE '7%' OR a.code LIKE '477%');

UPDATE journal_entry_lines l
  JOIN journal_entries e ON e.id = l.journal_entry_id AND e.source_type = 'PURCHASE_INVOICE'
  JOIN accounting_accounts a ON a.id = l.account_id
  JOIN purchase_invoices p ON p.id = e.source_id
   SET l.vat_rate = p.vat_percent
 WHERE l.vat_rate IS NULL
   AND p.vat_percent IS NOT NULL
   AND (a.code LIKE '6%' OR a.code LIKE '472%');
