-- REFLEJO-1 — Enlace de reflejo en facturas recibidas.
--
-- Cuando una factura EMITIDA dentro de la cartera de la asesoría tiene como
-- cliente a otra empresa de la cartera (match por NIF), se refleja en los
-- libros de esa empresa como factura RECIBIDA (gasto) + asiento, en estado
-- por validar. Estas columnas enlazan el gasto reflejado con su factura
-- emitida de origen para:
--   - idempotencia (no duplicar el reflejo si se re-valida),
--   - cascada de anulación/rectificación,
--   - trazabilidad en la UI ("Origen: factura de {emisor}").
--
-- source_company_id = la empresa EMISORA de la factura origen (la asesoría o
-- el cliente que facturó). source_sales_invoice_id = id de esa factura.
--
-- El UNIQUE permite múltiples NULL (las compras normales no reflejadas), pero
-- impide dos reflejos de la misma factura emitida en la misma empresa.

ALTER TABLE purchase_invoices
    ADD COLUMN source_sales_invoice_id CHAR(36) NULL AFTER journal_entry_id,
    ADD COLUMN source_company_id       CHAR(36) NULL AFTER source_sales_invoice_id;

CREATE UNIQUE INDEX uk_purchase_invoices_reflection
    ON purchase_invoices (company_id, source_sales_invoice_id);

CREATE INDEX ix_purchase_invoices_source_sales
    ON purchase_invoices (source_sales_invoice_id);
