-- ===========================================================================
-- V149 — Credenciales de Google OAuth POR INSTALACIÓN (bloque REG-3).
--
-- Decisión Benjamin (2026-06-26): no hay pasarela central; cada instalación
-- configura SUS PROPIAS credenciales de Google (Client ID + Secret de un proyecto
-- propio de tipo "Aplicación de escritorio"). Tabla de UNA sola fila (id=1) a
-- nivel instalación. El secreto se guarda CIFRADO (Jasypt), como el SMTP.
--
-- El backend es quien intercambia el "code" por el token con Google (el secreto
-- NUNCA sale al cliente). El cliente solo necesita el Client ID (no secreto).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS google_oauth_config (
    id INT NOT NULL DEFAULT 1,
    client_id VARCHAR(300) NULL,
    client_secret_encrypted VARCHAR(800) NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT ck_google_oauth_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO google_oauth_config (id, enabled) VALUES (1, FALSE);
