-- ===========================================================================
-- V37__supplier_extraction_templates.sql
--
-- PDF-TEMPLATES (2026-06-04): plantillas por proveedor para la
-- importación de facturas PDF.
--
-- Tras 4 facturas reales (Bloques Los Llanos, Amazon, Bellota, Solred)
-- quedó claro que las regex genéricas tienen techo. Cada formato nuevo
-- necesita ajustes. La solución sin IA: que el sistema APRENDA del
-- usuario.
--
-- Flujo:
--   1) Subida PDF → extractor genérico → resultado v1.
--   2) Si hay template guardado para el NIF emisor detectado, se
--      sobrescriben los campos con los valores fijos / reglas del
--      template.
--   3) UI muestra los campos EDITABLES.
--   4) Si el usuario corrige >= 2 campos, le ofrecemos "Guardar como
--      plantilla". Eso persiste los nuevos valores como `fixed_values`
--      (mismo valor para futuras facturas).
--
-- Diseño honesto:
--   - Empezamos solo con `fixed_values` (campos que son siempre iguales
--     por proveedor: supplier_name, vat_percent, prefijo de numero...).
--   - Reglas más complejas (label_after, regex_capture) las añadiremos
--     en un sub-slice cuando un caso real lo justifique.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS supplier_extraction_templates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    supplier_nif VARCHAR(40) NOT NULL,
    supplier_name VARCHAR(200) NULL,
    -- valores fijos JSON con la forma { field_key: value }
    -- field_key ∈ { supplier_name, vat_percent, vat_amount_strategy,
    -- base_amount_strategy, total_amount_strategy, invoice_number_prefix }
    -- value puede ser literal o "regex:<pattern>" para extracción dinámica.
    rules JSON NULL,
    uses_count INT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMP NULL,
    created_by_user_id CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_supplier_extraction_templates PRIMARY KEY (id),
    CONSTRAINT uk_set_company_nif UNIQUE (company_id, supplier_nif),
    CONSTRAINT fk_set_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_set_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_accounts (id),
    INDEX ix_set_nif (company_id, supplier_nif)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
