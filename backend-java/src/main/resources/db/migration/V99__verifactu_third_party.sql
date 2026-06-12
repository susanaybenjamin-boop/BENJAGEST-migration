-- =============================================================================
-- V99 — TPB-4: marca "emitida por tercero" en verifactu_registry
--
-- RD 1007/2023 + Orden HAC/1177/2024: el registro de facturación debe
-- indicar si la factura ha sido materialmente expedida por un tercero
-- (asesoría) e incluir su NIF y nombre. XSD AEAT lo modela como
-- EmitidaPorTerceroODestinatario + bloque Tercero (Nombre + NIF).
--
-- Modelo:
--   - issued_by_third_party BOOLEAN: indicador del campo
--     EmitidaPorTerceroODestinatario (TRUE → "T" en XML AEAT).
--   - third_party_company_id: id de la asesoría que materialmente
--     expidió. NULL cuando issued_by_third_party = FALSE.
--   - third_party_nif y third_party_name: snapshot del NIF/nombre
--     de la asesoría AL MOMENTO de la emisión. Snapshot porque la
--     asesoría puede cambiar de NIF/nombre con el tiempo y queremos
--     que la verificación de la cadena hash hist órica siga válida.
--
-- Compatibilidad de cadena hash:
--   - VerifactuRegistryService extiende el canonical (string que
--     hashea) SOLO cuando issued_by_third_party = TRUE. Para
--     facturas no-TPB el canonical no cambia, las cadenas históricas
--     siguen válidas y verificables.
--   - Para facturas TPB el canonical incluye al final
--     "&EmitidaPorTercero=T&TerceroNif={NIF}&TerceroNombre={NOMBRE}".
-- =============================================================================

ALTER TABLE verifactu_registry
    ADD COLUMN issued_by_third_party BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN third_party_company_id CHAR(36) NULL,
    ADD COLUMN third_party_nif VARCHAR(32) NULL,
    ADD COLUMN third_party_name VARCHAR(220) NULL,
    ADD CONSTRAINT fk_verifactu_registry_third_party
        FOREIGN KEY (third_party_company_id) REFERENCES companies(id);
