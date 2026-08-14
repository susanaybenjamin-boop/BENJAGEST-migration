-- CONC-1 — Catálogo de CONCEPTOS de factura ("elegir un concepto que ya usé
-- o que me creé", petición de Benjamin 2026-08-14).
--
-- NO se crea tabla nueva: `catalog_items` YA EXISTE desde V2 (con su FK
-- `sales_invoice_lines.catalog_item_id` puesta) y nunca se llegó a usar —
-- el javadoc de InvoiceLine lo dice literalmente: "queda preparado para el
-- slice futuro de catálogo de productos/servicios". Este slice la enciende.
--
-- Lo único que le faltaba a la tabla para guardar una línea de factura
-- COMPLETA son la retención y la identidad del tipo de IVA (FAC-IVA, V175:
-- con dos tipos al mismo % el porcentaje no basta). Todo aditivo: ni toca
-- seeds ni rompe lo existente (las columnas nacen NULL).
--
-- El "histórico" (conceptos ya usados en facturas anteriores) NO se copia
-- aquí: se calcula al vuelo desde sales_invoice_lines, así siempre refleja
-- la realidad y no hay dos sitios que puedan desincronizarse. Guardar un
-- concepto en el catálogo es un acto explícito del usuario.

ALTER TABLE catalog_items
    ADD COLUMN default_retention_percent DECIMAL(5,2) NULL,
    ADD COLUMN default_vat_rate_id CHAR(36) NULL;
