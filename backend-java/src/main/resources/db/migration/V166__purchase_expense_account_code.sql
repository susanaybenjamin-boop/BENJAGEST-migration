-- GAS-1 — Cuenta de gasto fija en facturas recibidas / recibos.
--
-- Permite fijar explícitamente la cuenta de gasto (6xx) de un gasto,
-- necesario para recibos SIN factura como el recibo de autónomo (cuota
-- RETA de la TGSS → cuenta 642 "Seguridad Social a cargo de la empresa").
--
-- Cuando es NULL, el asiento resuelve la cuenta con la cascada automática
-- habitual (regla aprendida → histórico del proveedor → clasificador →
-- fallback 600). Cuando trae un código, el asiento usa esa cuenta exacta
-- y no adivina (auto_proposed = FALSE).
ALTER TABLE purchase_invoices
    ADD COLUMN expense_account_code VARCHAR(20) NULL AFTER journal_entry_id;
