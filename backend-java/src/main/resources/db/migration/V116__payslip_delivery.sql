-- PAY-DELIVERY: entrega de la nómina al trabajador con acuse de recibo.
-- Orden ESS/2098/2014 / ET art. 29: el recibo de salarios debe entregarse al
-- trabajador. Registramos cuándo y por qué vía se entregó y cuándo el
-- trabajador acusó recibo (firma). Campos opcionales; no afectan al cálculo.
ALTER TABLE payslips
    ADD COLUMN IF NOT EXISTS delivered_at    DATE        NULL AFTER paid_at,
    ADD COLUMN IF NOT EXISTS delivery_method VARCHAR(30) NULL AFTER delivered_at,
    ADD COLUMN IF NOT EXISTS acknowledged_at DATE        NULL AFTER delivery_method;
