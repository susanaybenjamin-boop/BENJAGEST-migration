-- ===========================================================================
-- V22__consolidate_invoice_config.sql
--
-- Decisión 2026-06-04 (Benjamin): consolidar la configuración de
-- factura en un único punto. Antes había tres columnas distintas para
-- conceptos solapados:
--
--   - companies.invoice_footer        (V2 + V10, editable en C3 → Empresa)
--   - companies.invoice_footer_template (V13, editable en F5+ → VeriFactu)
--   - invoice_text_pie                (V15, editable en F5+ → Textos legales)
--
-- Lo mismo con "condiciones legales generales":
--
--   - companies.legal_terms           (V10, editable en C3 → Empresa)
--   - invoice_text_legal_terms        (V15, editable en F5+ → Textos legales)
--
-- A partir de aquí, las únicas fuentes de verdad son las columnas
-- invoice_text_* (V15) y verifactu_config.invoice_footer_template
-- (V13). Las dos columnas "fantasma" de C3 (invoice_footer y
-- legal_terms) se eliminan tras volcar su contenido a las
-- equivalentes V15 (si alguna empresa las tenía rellenadas y las
-- otras vacías).
--
-- C3 → pestaña Empresa queda solo con datos administrativos: razón
-- social, NIF, dirección, IBAN, datos de contacto.
-- F5+ → pestaña Configuración (de Facturación) absorbe pie + textos
-- legales + IBAN-en-factura + modo VeriFactu + cert + ruta + series.
-- ===========================================================================

-- 1) Volcado defensivo: si la empresa tenía rellenado en companies y
--    NO tenía en invoice_text_*, copiamos antes de borrar.

UPDATE companies
   SET invoice_text_pie = invoice_footer
 WHERE invoice_footer IS NOT NULL
   AND (invoice_text_pie IS NULL OR invoice_text_pie = '');

UPDATE companies
   SET invoice_text_legal_terms = legal_terms
 WHERE legal_terms IS NOT NULL
   AND (invoice_text_legal_terms IS NULL OR invoice_text_legal_terms = '');

-- 2) Eliminar las columnas duplicadas de companies.
-- IF EXISTS por defensa: si una versión anterior corrió el slice a
-- medias en algún entorno, no rompemos.

ALTER TABLE companies
    DROP COLUMN IF EXISTS invoice_footer;

ALTER TABLE companies
    DROP COLUMN IF EXISTS legal_terms;
