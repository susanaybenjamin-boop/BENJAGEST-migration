-- =============================================================================
-- V130 — FM-4: foto-evidencia opcional del fichaje de kiosco.
--
-- Decisión Benjamin 2026-06-17: foto OPCIONAL al fichar (configurable por
-- dispositivo, require_photo) como anti-fraude del "fichar por un compañero".
-- NO es reconocimiento facial — solo una instantánea-evidencia que ve el admin
-- (legalidad AEPD: foto-evidencia = dato personal art. 6, no biométrico art. 9).
--
-- La foto se guarda en disco; aquí solo la referencia + metadatos para listarla
-- y aplicar la retención (kiosk_devices.photo_retention_days).
-- =============================================================================

CREATE TABLE IF NOT EXISTS kiosk_punch_photos (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    event_id CHAR(36) NOT NULL,
    kiosk_device_id CHAR(36) NULL,
    file_path VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_kiosk_punch_photos PRIMARY KEY (id),
    CONSTRAINT fk_kiosk_photo_event FOREIGN KEY (event_id) REFERENCES time_clock_events (id) ON DELETE CASCADE,
    INDEX ix_kiosk_photo_company (company_id, created_at),
    INDEX ix_kiosk_photo_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
