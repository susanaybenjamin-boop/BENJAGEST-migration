-- ===========================================================================
-- V41__advisory_invitations.sql
--
-- ADVISORY-INVITATION (2026-06-05): comunicación asesoría↔empresario
-- por invitación.
--
-- Flujo:
--   1) Asesoría crea invitación (token random + expira en 7 días).
--   2) Asesoría comparte el link/token con el empresario (UI muestra
--      link copiable; opcionalmente se envía email best-effort).
--   3) Empresario logueado en su empresa ve invitaciones pendientes
--      donde invited_nif = self.tax_identifier OR invited_email =
--      current_user.email.
--   4) Al aceptar: companies.parent_company_id = advisory_company_id,
--      invitation.status = ACCEPTED.
--   5) Asesoría puede entonces switchear tenant a la empresa del
--      cliente (X-Company-Id) y gestionar gastos, certificados, etc.
--      en su nombre. La asesoría_link ya existe vía
--      AdvisoryService.listMyManagedClients.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS advisory_invitations (
    id CHAR(36) NOT NULL,
    advisory_company_id CHAR(36) NOT NULL,

    -- A quién va dirigida. Ambos campos son opcionales — basta con
    -- uno de los dos para que el empresario pueda reclamarla. El
    -- service valida que al menos uno encaje con la sesión que
    -- intenta aceptar.
    invited_email VARCHAR(200) NULL,
    invited_nif VARCHAR(40) NULL,
    invited_company_name VARCHAR(200) NULL,

    -- Tras ACCEPTED, se rellena con la empresa que la aceptó.
    invited_company_id CHAR(36) NULL,

    -- Token URL-safe (64 chars base62, ~370 bits de entropía).
    token VARCHAR(80) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMP NOT NULL,
    notes TEXT NULL,

    created_by_user_id CHAR(36) NULL,
    accepted_by_user_id CHAR(36) NULL,
    accepted_at TIMESTAMP NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_advisory_invitations PRIMARY KEY (id),
    CONSTRAINT uk_advisory_invitations_token UNIQUE (token),
    CONSTRAINT fk_advisory_invitations_advisory
        FOREIGN KEY (advisory_company_id) REFERENCES companies (id),
    CONSTRAINT fk_advisory_invitations_invited_company
        FOREIGN KEY (invited_company_id) REFERENCES companies (id),
    CONSTRAINT fk_advisory_invitations_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_advisory_invitations_accepted_by
        FOREIGN KEY (accepted_by_user_id) REFERENCES user_accounts (id),
    CONSTRAINT ck_advisory_invitations_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'REVOKED')),

    INDEX ix_advisory_invitations_advisory (advisory_company_id, status),
    INDEX ix_advisory_invitations_nif (invited_nif, status),
    INDEX ix_advisory_invitations_email (invited_email, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
