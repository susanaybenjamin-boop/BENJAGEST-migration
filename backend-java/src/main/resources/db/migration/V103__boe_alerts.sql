-- =============================================================================
-- V103 — BOE-RSS: alertas BOE sobre fiscal/laboral
--
-- BoeAlertService consulta diariamente la API datos abiertos del BOE
-- (https://www.boe.es/datosabiertos/api/boe/sumario/{YYYYMMDD}) y
-- filtra los artículos cuyo título contenga alguna keyword fiscal o
-- laboral: AEAT, IVA, IRPF, RD, factura, Verifactu, SEPE, Seguridad
-- Social, nómina, fichaje, salario, contrato.
--
-- Cada match crea una fila en boe_alerts (deduplicada por boe_id) y
-- dispara una notificación a todas las asesorías activas para que la
-- vean en su bandeja.
-- =============================================================================

CREATE TABLE IF NOT EXISTS boe_alerts (
    id                  CHAR(36)     NOT NULL,
    alert_date          DATE         NOT NULL,
    boe_id              VARCHAR(40)  NOT NULL,
    title               VARCHAR(500) NOT NULL,
    url                 VARCHAR(500) NULL,
    department          VARCHAR(200) NULL,
    keywords_matched    VARCHAR(300) NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_boe_alerts_boe_id (boe_id),
    KEY idx_boe_alerts_date (alert_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
