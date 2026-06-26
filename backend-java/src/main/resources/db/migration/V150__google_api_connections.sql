-- ===========================================================================
-- V150 — Conexión Google por EMPRESA para usar APIs (Gmail enviar, Calendar).
--
-- Bloque GOOGLE-UNIFICADO (Benjamin 2026-06-26): "ya que tengo el OAuth, ¿por qué
-- no enviar email y calendario por ahí?". Con UNA conexión OAuth (los Client ID/
-- Secret por instalación, V149) y los SCOPES adecuados se cubre login + Gmail +
-- Calendar, sin contraseña de aplicación SMTP.
--
-- Aquí se guarda el REFRESH TOKEN del usuario que conecta su Google para la
-- empresa (cifrado, Jasypt), para poder pedir access tokens y enviar/leer sin
-- re-consentir. Una fila por empresa (la cuenta que envía/sincroniza).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS google_api_connections (
    company_id VARCHAR(36) NOT NULL,
    google_email VARCHAR(255) NULL,
    refresh_token_encrypted VARCHAR(1200) NOT NULL,
    scopes VARCHAR(600) NULL,
    gmail_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    calendar_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
