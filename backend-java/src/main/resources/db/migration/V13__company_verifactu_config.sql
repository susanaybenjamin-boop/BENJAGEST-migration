-- ===========================================================================
-- V13__company_verifactu_config.sql
--
-- Configuracion de facturacion VeriFactu por empresa.
--
-- Modelo:
--   - verifactu_mode: OFF (no se envia a AEAT), TEST (entorno pruebas
--                     AEAT, sin valor legal), PROD (real).
--   - verifactu_certificate_id: FK opcional al certificado .p12 que se
--                     usa para firmar los XML de envio. NULL hasta que
--                     se sube uno y se elige.
--   - invoice_footer_template: plantilla de texto largo que aparece en
--                     el pie de cada factura (texto legal, IBAN, datos
--                     de contacto adicionales...).
--
-- Decisiones:
--   - Por defecto, todas las empresas arrancan en OFF. Activar VeriFactu
--     es una decision consciente del OWNER/ADMIN.
--   - El certificado se referencia con FK porque puede haber varios
--     subidos (uno por uso: VeriFactu, DEHu, SS RED, etc.) y queremos
--     decidir cual sirve para que.
-- ===========================================================================

ALTER TABLE companies
    ADD COLUMN verifactu_mode VARCHAR(10) NOT NULL DEFAULT 'OFF' AFTER invoice_footer,
    ADD COLUMN verifactu_certificate_id CHAR(36) NULL AFTER verifactu_mode,
    ADD COLUMN invoice_footer_template TEXT NULL AFTER verifactu_certificate_id,
    ADD CONSTRAINT ck_companies_verifactu_mode CHECK (verifactu_mode IN ('OFF', 'TEST', 'PROD')),
    ADD CONSTRAINT fk_companies_verifactu_certificate FOREIGN KEY (verifactu_certificate_id) REFERENCES digital_certificates (id);
