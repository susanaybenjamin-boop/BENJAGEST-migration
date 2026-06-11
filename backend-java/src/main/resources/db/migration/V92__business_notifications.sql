-- =========================================================
-- V92 — BUSINESS-NOTIF
-- Slice 2026-06-11 sesión tarde. Bandeja de notificaciones del
-- empresario (modo BUSINESS) — espejo de advisory_notifications
-- pero apuntando a business_company_id.
--
-- Tipos previstos (catálogo abierto, igual que en advisory):
--   * TAX_FILING_DUE_SOON  — vencimiento próximo de modelo AEAT.
--   * ADVISORY_MESSAGE     — la asesoría envió mensaje al thread.
--   * ADVISORY_DOCUMENT    — la asesoría subió documento al thread.
--   * INVOICE_OVERDUE      — factura emitida sin cobrar tras N días.
--   * SIF_ANOMALY          — anomalía cadena hash propia.
--
-- entity_ref formato "tipo:uuid" igual que advisory.
-- =========================================================

CREATE TABLE IF NOT EXISTS business_notifications (
    id                   CHAR(36)        NOT NULL,
    business_company_id  CHAR(36)        NOT NULL,
    related_company_id   CHAR(36)        NULL,
    notification_type    VARCHAR(40)     NOT NULL,
    severity             VARCHAR(10)     NOT NULL DEFAULT 'INFO',
    title                VARCHAR(200)    NOT NULL,
    message              VARCHAR(1000)   NULL,
    entity_ref           VARCHAR(200)    NULL,
    read_at              TIMESTAMP       NULL,
    dismissed_at         TIMESTAMP       NULL,
    created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_biz_notif_company (business_company_id),
    KEY idx_biz_notif_unread (business_company_id, read_at, dismissed_at),
    CONSTRAINT fk_biz_notif_company
        FOREIGN KEY (business_company_id) REFERENCES companies(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
