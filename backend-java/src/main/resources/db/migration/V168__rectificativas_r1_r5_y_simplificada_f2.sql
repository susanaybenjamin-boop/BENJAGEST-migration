-- V168 (bloque RECT, 2026-07-07): rectificativas R1-R5 + factura
-- simplificada F2 (RD 1619/2012 + RD 1007/2023).
--
-- 1) sales_invoices.rectification_code: causa legal de la rectificativa
--    (R1 error fundado en derecho / art. 80.Uno.Dos.Seis LIVA, R2
--    concurso art. 80.Tres, R3 credito incobrable art. 80.Cuatro,
--    R4 resto de causas, R5 rectificativa de factura simplificada).
--    NULL en NORMAL/PROFORMA/etc y en rectificativas historicas
--    (validadas antes de este bloque, que iban como F1 en la huella).
--
-- 2) sales_invoices.rectification_scope: ANNULMENT (anulacion total,
--    niega todo y la original pasa a VOIDED — comportamiento historico)
--    o PARTIAL (corrige importes; la original sigue VALIDATED con
--    vinculo). NULL historico = ANNULMENT (todas las rectificativas
--    previas eran anulaciones).
--
-- 3) customer_id pasa a NULL-able: la factura simplificada F2 no
--    identifica al destinatario (art. 7.1 RD 1619/2012). COLLATE
--    explicito para no romper el FK con customers (gotcha MariaDB 11.4:
--    el default de collation puede diferir y da errno 150).
--
-- 4) CHECK: una RECTIFYING siempre apunta a su original (hallazgo de la
--    auditoria 2026-07-07; verificado que no hay filas que lo violen).
--    Y una factura no-SIMPLIFIED sigue exigiendo cliente.
--
-- 5) verifactu_registry.invoice_type_code: el TipoFactura usado en la
--    huella (F1/F2/R1..R5) se guarda AL EMITIR y la verificacion de la
--    cadena usa el valor guardado. NULL historico = F1 — mismo patron
--    de compatibilidad que TPB-4 (las cadenas existentes siguen
--    verificables sin cambios).
--
-- 6) Serie reservada SIMP para simplificadas (mismo patron que V16:
--    RD 1619/2012 art. 6 — serie separada por construccion).

ALTER TABLE sales_invoices
    MODIFY customer_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    ADD COLUMN rectification_code VARCHAR(2) NULL AFTER rectifying_invoice_id,
    ADD COLUMN rectification_scope VARCHAR(12) NULL AFTER rectification_code;

ALTER TABLE sales_invoices
    ADD CONSTRAINT ck_sales_invoices_rectifying
        CHECK (invoice_type <> 'RECTIFYING' OR original_invoice_id IS NOT NULL),
    ADD CONSTRAINT ck_sales_invoices_customer
        CHECK (invoice_type = 'SIMPLIFIED' OR customer_id IS NOT NULL);

ALTER TABLE verifactu_registry
    ADD COLUMN invoice_type_code VARCHAR(2) NULL;

-- El CHECK de V2 solo admitia STANDARD/PROFORMA/RECTIFYING/TEST — sin
-- ampliarlo, la semilla SIMP de abajo revienta con error 4025.
ALTER TABLE invoice_series
    DROP CONSTRAINT ck_invoice_series_kind;
ALTER TABLE invoice_series
    ADD CONSTRAINT ck_invoice_series_kind
        CHECK (invoice_kind IN ('STANDARD', 'PROFORMA', 'RECTIFYING', 'SIMPLIFIED', 'TEST'));

INSERT INTO invoice_series (id, company_id, code, invoice_kind, numbering_type,
                            format_template, next_number, current_year, locked, active)
SELECT UUID(),
       c.id,
       'SIMP',
       'SIMPLIFIED',
       'BY_YEAR',
       '{CODE}-{YYYY}-{0000}',
       1,
       YEAR(CURRENT_DATE),
       FALSE,
       TRUE
  FROM companies c
 WHERE NOT EXISTS (
       SELECT 1 FROM invoice_series s
        WHERE s.company_id = c.id
          AND s.code = 'SIMP'
 );
