-- V172 (bloque OPTYPE, 2026-07-11): tipo de operación y deducibilidad por
-- factura, para clasificar automáticamente los modelos (303/390/349) y para
-- que el IVA/gasto NO deducible no cuente (diferencia real detectada al
-- comparar con las declaraciones de Benjamin).
--
-- operation_type — tipo de operación a efectos de IVA:
--   INTERIOR   nacional, régimen general (por defecto)
--   INTRACOM   adquisición/entrega intracomunitaria (UE)
--   IMPORT     importación / exportación (extracomunitaria)
--   ISP        inversión del sujeto pasivo (nacional, autorepercusión)
--
-- COMPRAS además:
--   vat_deductible_percent  % del IVA soportado deducible (0-100; 100 por
--                           defecto). 0 = IVA no deducible (no entra en el 303).
--   expense_deductible      si el GASTO es deducible en IRPF (130). Por defecto TRUE.
--   investment_good         bien de inversión (303 casillas 30/31). Por defecto FALSE.

ALTER TABLE purchase_invoices
    ADD COLUMN operation_type          VARCHAR(20)   NOT NULL DEFAULT 'INTERIOR',
    ADD COLUMN vat_deductible_percent  DECIMAL(5,2)  NOT NULL DEFAULT 100.00,
    ADD COLUMN expense_deductible      TINYINT(1)    NOT NULL DEFAULT 1,
    ADD COLUMN investment_good         TINYINT(1)    NOT NULL DEFAULT 0;

ALTER TABLE sales_invoices
    ADD COLUMN operation_type VARCHAR(20) NOT NULL DEFAULT 'INTERIOR';

ALTER TABLE purchase_invoices
    ADD CONSTRAINT ck_purchase_deductible_percent
        CHECK (vat_deductible_percent BETWEEN 0 AND 100);
