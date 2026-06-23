-- ===========================================================================
-- V142 — Interruptor del reflejo automático factura↔gasto (bloque REFLEJO).
--
-- Controla si los libros de esta empresa RECIBEN asientos/facturas reflejados
-- automáticamente cuando otra empresa de la cartera le emite (o le cobra/paga)
-- una factura. Por defecto TRUE (el reflejo está activo). El asesor puede
-- desmarcarlo por cliente desde la configuración de su ficha.
-- ===========================================================================

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS reflejo_auto_enabled BOOLEAN NOT NULL DEFAULT TRUE;
