-- ===========================================================================
-- V15__invoice_texts_and_migration.sql
--
-- Textos legales que aparecen en las facturas + soporte de migracion
-- desde otro programa con aviso de responsabilidad.
--
-- Modelo:
--   - 6 textos legales (uno por escenario): pie general, exencion IVA,
--     sujeto pasivo (reverse charge), IVA reducido, rectificativas,
--     terminos legales generales.
--   - show_iban: si la factura debe mostrar el IBAN de la empresa.
--   - migration_acknowledged_at + _by_user_id: cuando un OWNER/ADMIN
--     dejo constancia de que asume la responsabilidad de partir desde
--     un correlativo importado (CONTENDO lo llama "Asistente de
--     Migracion Fiscal").
--
-- Razon:
--   - Una empresa que viene de otro programa NO puede empezar en F-001
--     si ya tiene F-2025-0042: rompe la continuidad legal de la
--     numeracion. Por eso necesitamos permitir partir de un numero
--     arbitrario, pero solo con aceptacion explicita de responsabilidad.
--   - Una vez que BENJAGEST ha validado al menos una factura para una
--     serie en un ano, esa serie queda bloqueada (no se puede cambiar
--     codigo, formato ni tipo) hasta que cambie el ano. La logica vive
--     en SeriesService; la BD no fuerza nada porque el flag
--     locked= TRUE manual sigue siendo util para otros casos.
-- ===========================================================================

ALTER TABLE companies
    ADD COLUMN invoice_text_exempt TEXT NULL AFTER invoice_footer_template,
    ADD COLUMN invoice_text_reverse_charge TEXT NULL AFTER invoice_text_exempt,
    ADD COLUMN invoice_text_reduced_vat TEXT NULL AFTER invoice_text_reverse_charge,
    ADD COLUMN invoice_text_rectifying TEXT NULL AFTER invoice_text_reduced_vat,
    ADD COLUMN invoice_text_legal_terms TEXT NULL AFTER invoice_text_rectifying,
    ADD COLUMN invoice_show_iban BOOLEAN NOT NULL DEFAULT TRUE AFTER invoice_text_legal_terms,
    ADD COLUMN migration_acknowledged_at TIMESTAMP NULL AFTER invoice_show_iban,
    ADD COLUMN migration_acknowledged_by_user_id CHAR(36) NULL AFTER migration_acknowledged_at,
    ADD CONSTRAINT fk_companies_migration_user FOREIGN KEY (migration_acknowledged_by_user_id) REFERENCES user_accounts (id);
