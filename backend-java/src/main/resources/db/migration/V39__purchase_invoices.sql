-- ===========================================================================
-- V39__purchase_invoices.sql
--
-- PURCHASES-PERSIST (2026-06-05): persistencia de facturas de compras.
--
-- Decisiones de diseño confirmadas con Benjamin:
--
--   - NO guardamos el binario PDF. El usuario ya tiene el archivo en
--     su disco. Sí guardamos el SHA-256 para detectar duplicados al
--     re-subir la misma factura.
--   - NO hay status DRAFT/POSTED como en sales_invoices. El gasto se
--     da por contabilizado al guardar (POSTED). Para corregir hay que
--     anular (VOID) y crear uno nuevo, o registrar una rectificativa
--     en sub-slice futuro.
--   - Multi-factura PDF (Amazon empaqueta varias en un solo PDF): se
--     guarda una fila por factura individual con el mismo SHA-256 y
--     distinto invoice_index_in_pdf. La UNIQUE es la tripleta
--     (company, sha256, index).
--   - Si la empresa tiene plan contable activo (accounting_accounts
--     con códigos 600 / 472 / 400) y un fiscal_year OPEN, el service
--     genera un journal_entry vinculado (journal_entry_id NOT NULL).
--     Si no, la factura se guarda sin asiento y el sub-slice del
--     módulo contable lo creará al cerrar el ejercicio.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS purchase_invoices (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,

    -- Identificación del proveedor (sin FK porque puede ser un proveedor
    -- nuevo no registrado todavía en customers; solo guardamos snapshot).
    supplier_nif VARCHAR(40) NULL,
    supplier_name VARCHAR(200) NULL,

    -- Datos fiscales de la factura.
    invoice_number VARCHAR(80) NULL,
    invoice_date DATE NULL,
    base_amount DECIMAL(15,2) NULL,
    vat_percent DECIMAL(5,2) NULL,
    vat_amount DECIMAL(15,2) NULL,
    total_amount DECIMAL(15,2) NULL,

    -- Trazabilidad del documento original (sin guardarlo).
    document_sha256 CHAR(64) NULL,
    invoice_index_in_pdf INT NOT NULL DEFAULT 0,

    -- Estado y vinculación contable.
    status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
    journal_entry_id CHAR(36) NULL,
    notes TEXT NULL,

    -- Trazabilidad asesoría↔cliente (mismo patrón que digital_certificates):
    --   uploaded_by_user_id   = usuario que pulsó Guardar
    --   uploaded_by_company_id= empresa de ese usuario (== company_id si
    --                           la subió el propio empresario; == id de
    --                           la asesoría si subió por su cliente).
    uploaded_by_user_id CHAR(36) NULL,
    uploaded_by_company_id CHAR(36) NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_purchase_invoices PRIMARY KEY (id),
    CONSTRAINT uk_purchase_invoices_dedup
        UNIQUE (company_id, document_sha256, invoice_index_in_pdf),
    CONSTRAINT fk_purchase_invoices_company
        FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_purchase_invoices_journal
        FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT fk_purchase_invoices_user
        FOREIGN KEY (uploaded_by_user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_purchase_invoices_uploader_company
        FOREIGN KEY (uploaded_by_company_id) REFERENCES companies (id),
    CONSTRAINT ck_purchase_invoices_status
        CHECK (status IN ('POSTED', 'VOID')),

    INDEX ix_purchase_invoices_company_date (company_id, invoice_date),
    INDEX ix_purchase_invoices_supplier (company_id, supplier_nif),
    INDEX ix_purchase_invoices_status (company_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
