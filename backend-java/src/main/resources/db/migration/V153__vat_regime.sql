-- VAT-REGIME (versión base, decisión Benjamin 2026-06-27) — régimen de IVA de la
-- empresa: GENERAL (por defecto), PRORRATA (con %) o CASH_CRITERION (criterio de
-- caja, RD-Ley Ley 14/2013 — el IVA se declara al cobrar/pagar y la factura debe
-- mencionarlo). De momento se CAPTURA y se muestra; el efecto fino en el cálculo
-- (deducción prorrateada, diferimiento del criterio de caja) se afina con un caso
-- real. Sin COLLATE/FK -> ADD COLUMN simple, sin riesgo de errno 150.
ALTER TABLE companies
    ADD COLUMN vat_regime       VARCHAR(20)  NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN prorrata_percent DECIMAL(5,2) NULL;
