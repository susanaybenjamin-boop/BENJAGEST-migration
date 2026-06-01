-- ===========================================================================
-- V10__merge_issuer_into_company.sql
--
-- Unificacion de tablas fiscales: la empresa ES su propio emisor.
--
-- Decision arquitectonica (2026-06-01): la tabla `issuers` duplicaba
-- legal_name, tax_identifier, email, phone, address, iban, etc. respecto
-- a `companies`, y al editar los datos de la empresa la UI no podia
-- mantener coherencia sin sincronizacion manual (mismo problema que
-- arrastra CONTENDO con empresa_180 + emisor_180 + perfil_180).
--
-- Esta migracion:
--   1) Amplia `companies` con los campos fiscales que vivian solo en
--      `issuers` (direccion postal, IBAN, datos registrales, textos de
--      factura).
--   2) Copia los datos del emisor por defecto (`is_default = TRUE`) de
--      cada empresa a su fila correspondiente en `companies`.
--   3) Quita la FK `sales_invoices.issuer_id`: las facturas pasan a
--      atribuirse a la empresa (company_id ya esta presente). La columna
--      issuer_id queda nullable para no perder historico si alguien la
--      consulta, pero deja de tener referencia.
--   4) DROP TABLE issuers.
--
-- Si en el futuro hace falta el caso "una empresa factura con varias
-- razones sociales / NIFs" (FERRAPP, marcas), se crea una tabla
-- `additional_billing_profiles` enfocada en ese caso EDGE, no como
-- duplicacion del registro principal.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) Ampliar `companies` con los campos fiscales
-- ---------------------------------------------------------------------------
ALTER TABLE companies
    ADD COLUMN address_line VARCHAR(220) NULL AFTER website,
    ADD COLUMN city VARCHAR(100) NULL AFTER address_line,
    ADD COLUMN province VARCHAR(100) NULL AFTER city,
    ADD COLUMN postal_code VARCHAR(20) NULL AFTER province,
    ADD COLUMN country VARCHAR(80) NOT NULL DEFAULT 'Espana' AFTER postal_code,
    ADD COLUMN iban VARCHAR(34) NULL AFTER country,
    ADD COLUMN registry_information TEXT NULL AFTER iban,
    ADD COLUMN legal_terms TEXT NULL AFTER registry_information,
    ADD COLUMN invoice_footer TEXT NULL AFTER legal_terms;

-- ---------------------------------------------------------------------------
-- 2) Migrar datos del emisor por defecto de cada empresa
--
--    Solo copiamos los campos que no estan ya en `companies`. El
--    legal_name / tax_identifier / email / phone de la empresa son la
--    fuente verdadera; los del emisor podian estar desincronizados.
-- ---------------------------------------------------------------------------
UPDATE companies c
JOIN issuers i ON i.company_id = c.id
              AND i.is_default = TRUE
              AND i.active = TRUE
SET c.address_line         = COALESCE(c.address_line, i.address_line),
    c.city                 = COALESCE(c.city, i.city),
    c.province             = COALESCE(c.province, i.province),
    c.postal_code          = COALESCE(c.postal_code, i.postal_code),
    c.country              = COALESCE(c.country, i.country),
    c.iban                 = i.iban,
    c.registry_information = i.registry_information,
    c.legal_terms          = i.legal_terms,
    c.invoice_footer       = i.invoice_footer;

-- ---------------------------------------------------------------------------
-- 3) Quitar FK de sales_invoices.issuer_id
--
--    La columna se queda nullable; el codigo nuevo deja de usarla. Asi
--    los registros historicos no se rompen, pero el modelo deja de
--    propagar la duplicacion.
-- ---------------------------------------------------------------------------
ALTER TABLE sales_invoices DROP FOREIGN KEY fk_sales_invoices_issuer;
ALTER TABLE sales_invoices DROP COLUMN issuer_id;

-- ---------------------------------------------------------------------------
-- 4) Eliminar el catalogo del modulo "issuers" (era un sub-modulo de
--    billing). Asi el sidebar dinamico deja de mostrarlo automaticamente
--    en empresas que lo tuvieran activo.
-- ---------------------------------------------------------------------------
DELETE FROM company_modules
 WHERE module_id IN (SELECT id FROM module_catalog WHERE slug = 'issuers');
DELETE FROM module_catalog WHERE slug = 'issuers';

-- ---------------------------------------------------------------------------
-- 5) DROP TABLE issuers
-- ---------------------------------------------------------------------------
DROP TABLE issuers;
