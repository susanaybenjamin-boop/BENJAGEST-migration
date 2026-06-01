-- ===========================================================================
-- V11__merge_billing_profile_into_customer.sql
--
-- Hermana de V10 pero para el lado cliente.
--
-- La tabla `customer_billing_profiles` duplicaba `fiscal_name` y
-- `tax_identifier` con `customers`, y anadia metadatos de facturacion
-- (IVA por defecto, retencion, IBAN, metodo de pago, email/telefono de
-- facturacion, regimen fiscal). En la practica, casi todos los clientes
-- tienen UN solo perfil que coincide con sus datos legales.
--
-- Esta migracion:
--   1) Anade a `customers` las columnas que estaban en
--      `customer_billing_profiles` (sin re-duplicar fiscal_name /
--      tax_identifier, que ya viven en customers).
--   2) Copia los datos del primer perfil activo de cada cliente a su
--      fila correspondiente.
--   3) DROP TABLE customer_billing_profiles.
--
-- Si en el futuro hace falta "facturar a una entidad juridica distinta
-- del cliente principal" (caso edge), se crea una tabla
-- `customer_billing_recipients` enfocada en ese caso, no como
-- duplicacion del registro principal.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) Ampliar `customers` con los campos de facturacion
-- ---------------------------------------------------------------------------
ALTER TABLE customers
    ADD COLUMN fiscal_type VARCHAR(40) NULL AFTER customer_type,
    ADD COLUMN billing_email VARCHAR(180) NULL AFTER fiscal_type,
    ADD COLUMN billing_phone VARCHAR(40) NULL AFTER billing_email,
    ADD COLUMN default_vat_percent DECIMAL(5,2) NULL AFTER billing_phone,
    ADD COLUMN default_retention_percent DECIMAL(5,2) NULL AFTER default_vat_percent,
    ADD COLUMN vat_exempt BOOLEAN NOT NULL DEFAULT FALSE AFTER default_retention_percent,
    ADD COLUMN payment_method VARCHAR(40) NULL AFTER vat_exempt,
    ADD COLUMN iban VARCHAR(34) NULL AFTER payment_method;

-- ---------------------------------------------------------------------------
-- 2) Migrar datos del perfil de facturacion activo
--
--    Si un cliente tiene varios perfiles (caso atipico), nos quedamos
--    con el unico activo o el primero por created_at; el resto se
--    descartan al hacer DROP TABLE.
-- ---------------------------------------------------------------------------
UPDATE customers c
JOIN customer_billing_profiles cbp ON cbp.customer_id = c.id
                                   AND cbp.active = TRUE
SET c.fiscal_type               = cbp.fiscal_type,
    c.billing_email             = cbp.billing_email,
    c.billing_phone             = cbp.billing_phone,
    c.default_vat_percent       = cbp.default_vat_percent,
    c.default_retention_percent = cbp.default_retention_percent,
    c.vat_exempt                = cbp.vat_exempt,
    c.payment_method            = cbp.payment_method,
    c.iban                      = cbp.iban;

-- ---------------------------------------------------------------------------
-- 3) DROP TABLE customer_billing_profiles
-- ---------------------------------------------------------------------------
DROP TABLE customer_billing_profiles;
