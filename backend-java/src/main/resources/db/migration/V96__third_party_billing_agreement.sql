-- =============================================================================
-- V96 — TPB-1: Acuerdo previo de facturación por tercero
--
-- RD 1619/2012 art. 5: cuando la asesoría emite facturas materialmente
-- en nombre del cliente (titular fiscal), debe existir un acuerdo
-- previo por escrito que especifique a qué operaciones se refiere.
-- Sin este acuerdo no puede emitirse legalmente la primera factura
-- por tercero.
--
-- Modelo:
--   - Por par (asesoría, cliente) solo puede existir UN acuerdo
--     activo a la vez (PROPOSED o ACTIVE). Si la asesoría revoca y
--     vuelve a proponer, se inserta nueva fila (la antigua queda
--     REVOKED como histórico).
--   - Alcance granular: ventas / compras / modelos AEAT como
--     booleanos independientes.
--   - 2 modos de firma:
--       PIN_SESSION: cliente vinculado firma desde su UI con
--         checkbox + PIN de sesión.
--       OFFLINE_PDF: cliente NO vinculado (sin acceso a BENJAGEST);
--         asesoría descarga PDF, cliente firma físico, asesoría
--         sube el PDF escaneado y marca como verificado.
--   - signed_pdf_path: PDF resultante del acuerdo (con sello del PIN
--     o el escaneado offline). Descargable por ambas partes.
-- =============================================================================

CREATE TABLE IF NOT EXISTS third_party_billing_agreements (
    id                       CHAR(36)        NOT NULL,
    advisory_company_id      CHAR(36)        NOT NULL,
    client_company_id        CHAR(36)        NOT NULL,

    -- Alcance del acuerdo
    scope_sales              BOOLEAN         NOT NULL DEFAULT FALSE,
    scope_purchases          BOOLEAN         NOT NULL DEFAULT FALSE,
    scope_tax_models         BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Estado: PROPOSED → ACTIVE → REVOKED
    status                   VARCHAR(20)     NOT NULL DEFAULT 'PROPOSED',

    -- Iniciador
    initiated_by_advisory    BOOLEAN         NOT NULL,

    -- Firma
    signed_at                TIMESTAMP(6)    NULL,
    signed_method            VARCHAR(20)     NULL,   -- PIN_SESSION | OFFLINE_PDF
    signed_pdf_path          VARCHAR(500)    NULL,

    -- Revocación
    revoked_at               TIMESTAMP(6)    NULL,
    revoked_by_advisory      BOOLEAN         NULL,
    revoked_reason           VARCHAR(500)    NULL,

    -- Audit
    created_by_user_id       CHAR(36)        NULL,
    created_at               TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    KEY idx_tpb_advisory   (advisory_company_id),
    KEY idx_tpb_client     (client_company_id),
    KEY idx_tpb_status     (status),
    CONSTRAINT fk_tpb_advisory FOREIGN KEY (advisory_company_id)
        REFERENCES companies(id) ON DELETE CASCADE,
    CONSTRAINT fk_tpb_client   FOREIGN KEY (client_company_id)
        REFERENCES companies(id) ON DELETE CASCADE,
    CONSTRAINT ck_tpb_status CHECK (status IN ('PROPOSED', 'ACTIVE', 'REVOKED')),
    CONSTRAINT ck_tpb_signed_method CHECK (
        signed_method IS NULL
        OR signed_method IN ('PIN_SESSION', 'OFFLINE_PDF')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
