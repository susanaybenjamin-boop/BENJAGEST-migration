-- COB-MULTI (2026-10-18): "Cobrar varias" cobra ahora por vencimientos
-- (asiento 572/430 + vencimiento saldado). Cada fila del registro de cobros
-- apunta al vencimiento que saldó, para que "Deshacer pago" la borre también.
-- Aditivo: las filas antiguas quedan con NULL.
ALTER TABLE sales_invoice_payments
    ADD COLUMN due_date_id CHAR(36) NULL,
    ADD INDEX idx_sip_due_date_id (due_date_id);
