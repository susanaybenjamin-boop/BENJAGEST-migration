-- ===========================================================================
-- V6__issuer_default_flag.sql
-- Anade el concepto de "emisor activo" a la tabla issuers: cada empresa
-- puede tener varios emisores pero solo uno es el activo (el que se usa
-- por defecto al emitir facturas, registrar gastos, etc.).
--
-- La unicidad de "un solo activo por empresa" se garantiza en codigo
-- (IssuerService.markAsDefault hace clearDefaults + setDefault dentro
-- de una transaccion). No se usa indice unico parcial porque MariaDB
-- no lo soporta de forma directa.
-- ===========================================================================

ALTER TABLE issuers
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE AFTER active;

CREATE INDEX ix_issuers_company_default ON issuers (company_id, is_default);

-- El emisor sembrado en V3 para la empresa demo pasa a ser el activo
-- por defecto. Sin esto, una empresa con un solo emisor no tendria
-- ninguno marcado como activo y la UI quedaria sin valor que mostrar.
UPDATE issuers
   SET is_default = TRUE
 WHERE company_id = '11111111-1111-1111-1111-111111111111'
   AND id = '40000000-0000-0000-0000-000000000001';
