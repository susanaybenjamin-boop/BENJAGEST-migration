-- ===========================================================================
-- V22__consolidate_invoice_config.sql  (fix 2026-06-04: columna destino)
--
-- Decisión 2026-06-04 (Benjamin): consolidar la configuración de
-- factura en un único punto. Antes había dos columnas distintas para
-- el pie de factura:
--
--   - companies.invoice_footer          (V2 + V10, editable en C3 → Empresa)
--   - companies.invoice_footer_template (V13, editable en F5+ → VeriFactu)
--
-- Y dos para "condiciones legales generales":
--
--   - companies.legal_terms             (V10, editable en C3 → Empresa)
--   - companies.invoice_text_legal_terms (V15, editable en F5+ → Textos legales)
--
-- A partir de aquí, las únicas fuentes de verdad son
-- `invoice_footer_template` (V13) para el pie y `invoice_text_legal_terms`
-- (V15) para las condiciones legales. Las dos columnas "fantasma" de C3
-- (`invoice_footer` y `legal_terms`) se eliminan tras volcar su contenido
-- a las equivalentes nuevas (si alguna empresa las tenía rellenadas y
-- las otras vacías).
--
-- C3 → pestaña Empresa queda solo con datos administrativos: razón
-- social, NIF, dirección, IBAN, datos de contacto.
-- F5+ → pestaña Configuración (de Facturación) absorbe pie + textos
-- legales + IBAN-en-factura + modo VeriFactu + cert + ruta + series.
--
-- NOTA DEL FIX: la versión inicial intentaba volcar a una columna
-- `invoice_text_pie` que nunca existió (V15 nunca la creó). Como esta
-- migración nunca se llegó a aplicar con éxito en ningún entorno (era
-- imposible), corregirla in-place es seguro: el repair() del bean
-- FlywayConfig limpia la entrada FAILED del schema_history y reintenta.
-- ===========================================================================

-- 1) Volcado defensivo: si la empresa tenía rellenado en companies y
--    NO tenía en la columna destino, copiamos antes de borrar.
--    Se hace condicional por columna existente — en BBDD donde una
--    migración anterior haya quitado `invoice_footer` o `legal_terms`,
--    el ALTER de abajo es no-op y este UPDATE se salta de forma natural.

-- ATENCION: para que el UPDATE no falle si las columnas origen no
-- existen, hacemos el volcado dentro de un bloque condicional. MariaDB
-- no tiene IF EXISTS para UPDATE — usamos PREPARE dinamico.

SET @col_invoice_footer = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'companies'
       AND column_name = 'invoice_footer');

SET @sql_pie = IF(@col_invoice_footer > 0,
    'UPDATE companies
        SET invoice_footer_template = invoice_footer
      WHERE invoice_footer IS NOT NULL
        AND invoice_footer != ''''
        AND (invoice_footer_template IS NULL OR invoice_footer_template = '''')',
    'SELECT 1');
PREPARE stmt_pie FROM @sql_pie;
EXECUTE stmt_pie;
DEALLOCATE PREPARE stmt_pie;

SET @col_legal_terms = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'companies'
       AND column_name = 'legal_terms');

SET @sql_legal = IF(@col_legal_terms > 0,
    'UPDATE companies
        SET invoice_text_legal_terms = legal_terms
      WHERE legal_terms IS NOT NULL
        AND legal_terms != ''''
        AND (invoice_text_legal_terms IS NULL OR invoice_text_legal_terms = '''')',
    'SELECT 1');
PREPARE stmt_legal FROM @sql_legal;
EXECUTE stmt_legal;
DEALLOCATE PREPARE stmt_legal;

-- 2) Eliminar las columnas duplicadas de companies.
-- IF EXISTS por defensa: si una versión anterior corrió el slice a
-- medias en algún entorno, no rompemos.

ALTER TABLE companies
    DROP COLUMN IF EXISTS invoice_footer;

ALTER TABLE companies
    DROP COLUMN IF EXISTS legal_terms;
