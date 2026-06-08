-- =============================================================================
-- V71 — Colaboración entre asesorías (sub-asesorías / asesores externos).
--
-- Caso de uso: la asesoría A tiene clientes y quiere que parte de la
-- carga la haga la asesoría B (un asesor fiscal externo, una sub-
-- asesoría, un especialista por horas...). A invita a B por email,
-- B acepta y desde ese momento A puede asignar clientes a los
-- empleados de B desde el módulo Equipo (extensión L4-7).
--
-- Diferencias frente a advisory_invitations (V41):
--   - advisory_invitations: asesoría ←→ empresario CLIENT.
--   - advisory_collaborations: asesoría ←→ asesoría (entre PARES).
--
-- Modelo:
--   advisory_company_id     → la asesoría que tiene los clientes (anfitrión).
--   partner_advisory_id     → la asesoría colaboradora (NULL hasta aceptar
--                              si invitada por email y aún no identificada).
--   invited_email           → email del OWNER de la partner. Identifica
--                              al destinatario antes de que acepte; al
--                              aceptar resolvemos partner_advisory_id
--                              desde el user_account de quien acepta.
--   status                  → PENDING / ACCEPTED / REJECTED / REVOKED.
--
-- Restricciones:
--   - UK (advisory_company_id, invited_email) entre filas activas (la
--     misma asesoría no puede tener 2 invitaciones PENDING/ACCEPTED al
--     mismo email). Si una está REJECTED/REVOKED se puede re-invitar.
-- =============================================================================

CREATE TABLE advisory_collaborations (
    id CHAR(36) NOT NULL,
    advisory_company_id CHAR(36) NOT NULL,
    partner_advisory_id CHAR(36) NULL,
    invited_email VARCHAR(180) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    invited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invited_by_user_id CHAR(36) NULL,
    accepted_at TIMESTAMP NULL,
    accepted_by_user_id CHAR(36) NULL,
    revoked_at TIMESTAMP NULL,
    revoked_by_user_id CHAR(36) NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_advisory_collaborations PRIMARY KEY (id),
    CONSTRAINT fk_advisory_collab_advisory FOREIGN KEY (advisory_company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_advisory_collab_partner FOREIGN KEY (partner_advisory_id)
        REFERENCES companies (id),
    CONSTRAINT fk_advisory_collab_invited_by FOREIGN KEY (invited_by_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT fk_advisory_collab_accepted_by FOREIGN KEY (accepted_by_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT fk_advisory_collab_revoked_by FOREIGN KEY (revoked_by_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT ck_advisory_collab_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'REVOKED')
    ),
    INDEX ix_advisory_collab_advisory_status (advisory_company_id, status),
    INDEX ix_advisory_collab_invited_email (invited_email),
    INDEX ix_advisory_collab_partner (partner_advisory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
