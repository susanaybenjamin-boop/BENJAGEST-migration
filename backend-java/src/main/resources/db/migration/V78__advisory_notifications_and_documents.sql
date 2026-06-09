-- V78 — Notificaciones del asesor + documentos compartidos asesoría↔cliente.
--
-- Dos features pequeñas en una sola migración porque comparten patrón:
--   - Ámbito por (advisory_company_id, client_company_id).
--   - Estado simple con read_at o status.
--   - Audit chain con created_at + created_by_user_id.
--
-- 1) advisory_notifications: bandeja interna del asesor.
--    Eventos: cliente subió documento, vence un modelo AEAT en 7 días,
--    contrato termina en 30 días, invitación aceptada, factura vence,
--    etc. Distinto de los `audit_events` (que son trail de auditoría
--    detallada): aquí solo los hitos que el asesor debe ver y descartar.
--
-- 2) advisory_documents: archivos compartidos en ambos sentidos.
--    El cliente sube su declaración trimestral, el asesor sube el
--    borrador firmado, etc. Estados: UPLOADED → REVIEWED → ACCEPTED |
--    REJECTED. Cada cambio queda con `reviewed_by_user_id` y `note`.

-- ----------------------------------------------------------------
-- 1) Notificaciones del asesor
-- ----------------------------------------------------------------

CREATE TABLE advisory_notifications (
    id                    CHAR(36)     NOT NULL,
    advisory_company_id   CHAR(36)     NOT NULL,
    -- Cliente al que se refiere la notificación. NULL si es del
    -- workflow interno de la asesoría (ej. fin de ejercicio fiscal).
    client_company_id     CHAR(36)     NULL,
    notification_type     VARCHAR(60)  NOT NULL,
    -- Severidad ayuda al render: INFO (azul), WARNING (amber),
    -- URGENT (rojo). El cron puede subir severidad al acercarse la
    -- fecha (URGENT si <3 días al vencimiento, WARNING si <7).
    severity              VARCHAR(20)  NOT NULL DEFAULT 'INFO',
    title                 VARCHAR(200) NOT NULL,
    message               TEXT         NULL,
    -- Entidad relacionada para navegación contextual (al hacer click
    -- en la notificación, la UI abre la entidad). Formato libre:
    -- "contract:uuid", "tax_filing:uuid", "invoice:uuid", etc.
    entity_ref            VARCHAR(120) NULL,
    read_at               TIMESTAMP    NULL,
    dismissed_at          TIMESTAMP    NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_adv_notif PRIMARY KEY (id),
    CONSTRAINT fk_adv_notif_advisory FOREIGN KEY (advisory_company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_adv_notif_client FOREIGN KEY (client_company_id)
        REFERENCES companies (id),
    CONSTRAINT ck_adv_notif_severity CHECK (severity IN ('INFO', 'WARNING', 'URGENT')),
    INDEX ix_adv_notif_unread (advisory_company_id, read_at, dismissed_at, created_at DESC),
    INDEX ix_adv_notif_client (client_company_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------
-- 2) Documentos compartidos
-- ----------------------------------------------------------------

CREATE TABLE advisory_documents (
    id                    CHAR(36)     NOT NULL,
    advisory_company_id   CHAR(36)     NOT NULL,
    client_company_id     CHAR(36)     NOT NULL,
    -- direction = quién lo subió (A2C asesor→cliente, C2A cliente→asesor).
    direction             VARCHAR(10)  NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    file_path             VARCHAR(500) NOT NULL,
    file_size_bytes       BIGINT       NOT NULL DEFAULT 0,
    mime_type             VARCHAR(120) NULL,
    -- Estado: UPLOADED (subido, sin revisar), REVIEWED (visto pero
    -- sin decisión), ACCEPTED (aceptado por el receptor), REJECTED
    -- (rechazado, motivo en note).
    status                VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    note                  TEXT         NULL,
    uploaded_by_user_id   CHAR(36)     NULL,
    reviewed_by_user_id   CHAR(36)     NULL,
    reviewed_at           TIMESTAMP    NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_adv_doc PRIMARY KEY (id),
    CONSTRAINT fk_adv_doc_advisory FOREIGN KEY (advisory_company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_adv_doc_client FOREIGN KEY (client_company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_adv_doc_uploader FOREIGN KEY (uploaded_by_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT fk_adv_doc_reviewer FOREIGN KEY (reviewed_by_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT ck_adv_doc_direction CHECK (direction IN ('A2C', 'C2A')),
    CONSTRAINT ck_adv_doc_status CHECK (status IN ('UPLOADED', 'REVIEWED', 'ACCEPTED', 'REJECTED')),
    INDEX ix_adv_doc_thread (advisory_company_id, client_company_id, created_at DESC),
    INDEX ix_adv_doc_pending (advisory_company_id, status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
