-- IMP-H1: soporte de importacion historica CONTENDO.
--
-- 1) Nuevo invoice_type = 'HISTORICAL' para facturas de venta importadas del
--    sistema anterior. Son documentos YA emitidos legalmente en CONTENDO;
--    NUNCA entran en la cadena VeriFactu/SIF (RD 1007/2023) porque eso
--    fabricaria una cadena de hash falsa sobre un hecho ya cerrado. Misma
--    doctrina que V151 (invoice_migration_baseline). El bypass se apoya en el
--    precedente limpio de PROFORMA en SalesInvoiceService.validateInternal.
--
-- 2) Nuevo formato de importacion 'CSV_CONTENDO' y target 'FULL_HISTORY'
--    (un unico fichero reconstruye asientos + facturas + terceros a la vez).
--
-- 3) content_sha256 para idempotencia: reimportar el mismo fichero se rechaza.

ALTER TABLE sales_invoices
    DROP CONSTRAINT ck_sales_invoices_type;
ALTER TABLE sales_invoices
    ADD CONSTRAINT ck_sales_invoices_type CHECK (invoice_type IN (
        'NORMAL', 'PROFORMA', 'RECTIFYING', 'SIMPLIFIED', 'TEST', 'HISTORICAL'
    ));

ALTER TABLE external_import_batches
    DROP CONSTRAINT ck_eib_format;
ALTER TABLE external_import_batches
    ADD CONSTRAINT ck_eib_format CHECK (source_format IN (
        'CSV', 'CONTASOL', 'A3', 'SAGE', 'XML_ESPI', 'JSON_BENJAGEST', 'CSV_CONTENDO'
    ));

ALTER TABLE external_import_batches
    DROP CONSTRAINT ck_eib_target;
ALTER TABLE external_import_batches
    ADD CONSTRAINT ck_eib_target CHECK (target_kind IN (
        'ACCOUNTS', 'JOURNAL_ENTRIES', 'INVOICES_SALES', 'INVOICES_PURCHASE',
        'CUSTOMERS', 'SUPPLIERS', 'FIXED_ASSETS', 'LOANS', 'FULL_HISTORY'
    ));

ALTER TABLE external_import_batches
    ADD COLUMN content_sha256 CHAR(64) NULL;

CREATE INDEX ix_eib_company_sha ON external_import_batches (company_id, content_sha256);
