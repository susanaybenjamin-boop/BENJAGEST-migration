-- =============================================================================
-- V105 — TPB: token de revocacion para cliente sin cuenta
--
-- Decision Benjamin 2026-06-12: el cliente sin vinculo que firmo via
-- Magic Link tambien debe poder revocar. Sin un mecanismo simetrico
-- al de firma queda atrapado en un acuerdo que solo la asesoria puede
-- deshacer (asimetria contraria al RD 1619/2012 art. 5).
--
-- Diseno:
--   - Reusamos la tabla tpb_magic_link_tokens anadiendo purpose
--     (SIGN | REVOKE).
--   - El OTP se entrega DESPUES, cuando el cliente entra a la pagina
--     de revocacion (anti-phishing: el enlace solo identifica al
--     cliente, no es prueba de revocacion por si solo).
--   - Por eso otp_hash pasa a ser NULL. Cuando es SIGN se setea al
--     crear el token. Cuando es REVOKE se setea al solicitar OTP.
--   - revoked_pdf_path: si la revocacion sale adelante, almacenamos el
--     PDF con la firma electronica de la revocacion (similar al PDF
--     del acuerdo firmado).
-- =============================================================================

ALTER TABLE tpb_magic_link_tokens
    MODIFY otp_hash CHAR(64) NULL;

ALTER TABLE tpb_magic_link_tokens
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'SIGN' AFTER agreement_id,
    ADD CONSTRAINT ck_tpb_magic_link_purpose CHECK (
        purpose IN ('SIGN', 'REVOKE')
    ),
    ADD COLUMN otp_requested_at TIMESTAMP(6) NULL,
    ADD COLUMN revocation_pdf_path VARCHAR(500) NULL;

-- Permitir el estado REVOKED con metodo MAGIC_LINK_OTP en el acuerdo
-- (ya estaba aceptado, solo lo reflejamos en comentario para claridad).

CREATE INDEX IF NOT EXISTS idx_tpb_magic_link_purpose
    ON tpb_magic_link_tokens (purpose);
