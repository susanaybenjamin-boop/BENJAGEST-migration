-- ===========================================================================
-- V175 — FAC-IVA: identidad del TIPO de IVA en las líneas de factura.
--
-- Bug (Benjamin 2026-07-10): la línea solo guardaba el PORCENTAJE. Con dos
-- tipos configurados al mismo % (p. ej. "0% exento art. 20" y "0% no
-- sujeto"), el PDF imprimía el texto legal genérico de la empresa sin saber
-- cuál de los dos era. Ahora:
--   · vat_rates.legal_text — texto legal PROPIO de cada tipo, que el PDF
--     imprime cuando alguna línea de la factura usa ese tipo. Si está vacío
--     se cae al comportamiento anterior (textos por empresa según %).
--   · sales_invoice_lines.vat_rate_id — QUÉ tipo del catálogo eligió la
--     línea (NULL en histórico/imports: siguen funcionando solo con el %).
-- ===========================================================================

ALTER TABLE vat_rates
    ADD COLUMN legal_text TEXT NULL;

ALTER TABLE sales_invoice_lines
    ADD COLUMN vat_rate_id CHAR(36) NULL;
