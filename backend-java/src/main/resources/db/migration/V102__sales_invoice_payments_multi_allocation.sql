-- =============================================================================
-- V102 — MULTI-ALLOCATION: un pago real reparte importe entre varias facturas
--
-- Modelo previo: 1 fila sales_invoice_payments = 1 pago a 1 factura.
-- Limitación: un cliente paga 350€ que saldan 2 facturas (200€ + 150€)
-- — antes había que registrar 2 pagos independientes con la misma
-- referencia bancaria y el asesor tenía que recordar que son el mismo
-- pago real.
--
-- Solución: nueva columna payment_id (UUID) que agrupa las
-- allocations del mismo pago bancario. Cuando viene un pago multi-
-- factura, se genera 1 UUID y se inserta 1 fila por cada factura
-- afectada, todas con el mismo payment_id + reference + payment_date.
-- =============================================================================

ALTER TABLE sales_invoice_payments
    ADD COLUMN payment_id CHAR(36) NULL,
    ADD INDEX idx_sip_payment_id (payment_id);
