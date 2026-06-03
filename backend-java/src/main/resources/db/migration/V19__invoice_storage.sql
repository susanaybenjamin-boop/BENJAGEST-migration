-- ===========================================================================
-- V19__invoice_storage.sql
--
-- Slice F-STORAGE — Almacenamiento documental de facturas.
--
-- Hasta este slice los PDFs se generaban "on the fly" cada vez que se
-- pedian. Para conservacion legal (RD 1007/2023 + Ley General Tributaria
-- art. 70: minimo 4 anyos de prescripcion) la copia debe quedar fijada
-- en el momento de la validacion y no regenerarse. Aqui anyadimos:
--
--   - companies.invoice_storage_root — ruta raiz local donde la empresa
--     quiere almacenar sus facturas. NULL = usar default del backend
--     (configurable en application.properties como
--     `benjagest.invoices.storageRoot`).
--   - sales_invoices.pdf_path — ruta absoluta del PDF generado al
--     validar. NULL en borradores y en facturas legacy (anteriores a
--     este slice — el endpoint /pdf hace fallback a generacion
--     on-the-fly si encuentra NULL).
--
-- Estructura de carpetas que asume el servicio (compatible con
-- CONTENDO):
--
--   {root}/{companyId}/{YYYY}/T{1|2|3|4}/{invoiceNumber}.pdf
--
-- El trimestre se calcula desde invoice_date. CompanyId garantiza el
-- aislamiento multi-tenant tambien a nivel de filesystem — una empresa
-- no puede llegar al disco de otra ni con bug de path.
--
-- Sobre el tamanyo VARCHAR(500): las rutas Windows pueden llegar a 260
-- caracteres por path, mas el companyId UUID (36), mas extension... 500
-- da margen para roots largos sin riesgo de truncado.
-- ===========================================================================

ALTER TABLE companies
    ADD COLUMN invoice_storage_root VARCHAR(500) NULL AFTER invoice_footer_template;

ALTER TABLE sales_invoices
    ADD COLUMN pdf_path VARCHAR(500) NULL AFTER notes;
