-- =============================================================================
-- V98 — TPB-3: aceptación factura-a-factura por el cliente
--
-- RD 1619/2012 art. 5: cuando la asesoría emite materialmente una
-- factura en nombre del titular, cada factura debe pasar por un paso
-- de aceptación del titular ANTES de entregarla al destinatario. La
-- factura emitida por la asesoría queda en estado
-- PENDING_CLIENT_APPROVAL; el cliente la aprueba o la rechaza desde su
-- sesión. Aprobada → VALIDATED (normal). Rechazada → vuelve a DRAFT
-- con el motivo registrado y la asesoría la edita.
--
-- Cambios:
--   - sales_invoices.status acepta nuevo valor PENDING_CLIENT_APPROVAL.
--   - 4 columnas nuevas para registrar la evidencia de aceptación:
--       client_approval_state: APPROVED / REJECTED / NULL.
--       client_approval_at: timestamp.
--       client_approval_by_user_id: quién aprobó/rechazó.
--       client_rejection_reason: motivo si rechaza.
-- =============================================================================

ALTER TABLE sales_invoices
    DROP CONSTRAINT ck_sales_invoices_status;

ALTER TABLE sales_invoices
    ADD CONSTRAINT ck_sales_invoices_status CHECK (
        status IN ('DRAFT', 'PENDING_CLIENT_APPROVAL', 'VALIDATED',
                   'CANCELLED', 'VOIDED')
    ),
    ADD COLUMN client_approval_state VARCHAR(20) NULL,
    ADD COLUMN client_approval_at TIMESTAMP(6) NULL,
    ADD COLUMN client_approval_by_user_id CHAR(36) NULL,
    ADD COLUMN client_rejection_reason VARCHAR(500) NULL,
    ADD CONSTRAINT ck_sales_invoices_client_approval_state CHECK (
        client_approval_state IS NULL
        OR client_approval_state IN ('APPROVED', 'REJECTED')
    );
