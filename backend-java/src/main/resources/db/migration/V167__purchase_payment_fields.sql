-- GAS-2 — Pago de facturas recibidas / recibos (segundo asiento).
--
-- El gasto se registra primero como devengo (Debe 6xx / Haber 400 proveedor)
-- y el PAGO se registra después, como en CONTENDO, generando un segundo
-- asiento (Debe 400 proveedor / Haber 572 banco). Estas columnas guardan
-- el estado de pago del gasto para que la UI muestre pagado/pendiente y para
-- impedir pagar dos veces el mismo gasto.
ALTER TABLE purchase_invoices
    ADD COLUMN paid BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
    ADD COLUMN paid_date DATE NULL AFTER paid,
    ADD COLUMN payment_account_code VARCHAR(20) NULL AFTER paid_date;
