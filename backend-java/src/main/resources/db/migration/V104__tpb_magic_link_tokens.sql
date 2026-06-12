-- =============================================================================
-- V104 — TPB Magic Link + OTP: firma electronica simple para clientes
-- que no quieren instalar BENJAGEST.
--
-- Decision Benjamin 2026-06-12: tras bloquear el flujo offline-PDF (que
-- no verificaba firma) el bloqueo dejaba al cliente sin cuenta con
-- demasiada friccion (registrarse + invitacion + PIN solo para firmar).
-- Sustituimos por flujo "Magic Link + OTP":
--
--   1. La asesoria envia desde BENJAGEST un magic link al email/SMS del
--      cliente.
--   2. El cliente abre el enlace → pagina web simple servida por el
--      backend (sin instalar nada).
--   3. Lee el PDF del acuerdo.
--   4. Recibe un OTP de 6 digitos por el mismo canal.
--   5. Introduce el OTP y firma.
--   6. El backend guarda IP, user-agent, hora y OTP usado como
--      evidencia. Marca el acuerdo como ACTIVE con
--      signed_method='MAGIC_LINK_OTP'.
--
-- Cumple eIDAS art. 25 (firma electronica simple) — hay constancia
-- identificativa del firmante via cadena email/SMS + OTP, mas IP/UA.
-- No es firma cualificada pero si es probatoria ante AEAT/tribunales.
--
-- El token expira a las 24h. Si el cliente no firma a tiempo, la
-- asesoria reenvia el link. Se acepta hasta 5 intentos de OTP por token
-- antes de invalidarlo (anti-fuerza-bruta).
-- =============================================================================

CREATE TABLE IF NOT EXISTS tpb_magic_link_tokens (
    id                       CHAR(36)        NOT NULL,
    agreement_id             CHAR(36)        NOT NULL,

    -- Token: 32 bytes random en hex (64 chars). Va en la URL.
    token                    CHAR(64)        NOT NULL,

    -- OTP: 6 digitos. Guardamos hash SHA-256 (64 chars hex) para que
    -- ni siquiera el operador del backend pueda leerlo de la BD.
    otp_hash                 CHAR(64)        NOT NULL,

    -- Canal de envio
    recipient_email          VARCHAR(255)    NULL,
    recipient_phone          VARCHAR(30)     NULL,

    -- Ventana de validez
    sent_at                  TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at               TIMESTAMP(6)    NOT NULL,
    used_at                  TIMESTAMP(6)    NULL,

    -- Evidencia para la auditoria
    signer_ip                VARCHAR(64)     NULL,
    signer_user_agent        VARCHAR(500)    NULL,

    -- Anti fuerza bruta del OTP
    attempt_count            INT             NOT NULL DEFAULT 0,
    invalidated_at           TIMESTAMP(6)    NULL,
    invalidated_reason       VARCHAR(120)    NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_tpb_magic_link_token UNIQUE (token),
    CONSTRAINT fk_tpb_magic_link_agreement FOREIGN KEY (agreement_id)
        REFERENCES third_party_billing_agreements(id) ON DELETE CASCADE,
    KEY idx_tpb_magic_link_agreement (agreement_id),
    KEY idx_tpb_magic_link_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Permitir el nuevo signed_method.
ALTER TABLE third_party_billing_agreements
    DROP CONSTRAINT ck_tpb_signed_method;

ALTER TABLE third_party_billing_agreements
    ADD CONSTRAINT ck_tpb_signed_method CHECK (
        signed_method IS NULL
        OR signed_method IN ('PIN_SESSION', 'OFFLINE_PDF', 'MAGIC_LINK_OTP')
    );
