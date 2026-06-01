-- ===========================================================================
-- V12__revoked_refresh_tokens.sql
--
-- Denylist de refresh tokens para soportar logout efectivo.
--
-- Hasta aqui, un logout solo borraba los tokens del cliente (UI). El
-- refresh token seguia siendo valido hasta su caducidad natural (30
-- dias por defecto): si alguien tenia ese refresh, podia seguir
-- pidiendo accesses nuevos aunque el usuario hubiera cerrado sesion.
--
-- Con esta tabla, AuthService.refresh comprueba si el jti del refresh
-- esta en revoked_refresh_tokens; si lo esta, devuelve 401. El logout
-- inserta el jti aqui. La tabla se purga periodicamente para borrar
-- entradas mas viejas que la TTL del refresh (no hace falta guardar
-- denylist eternamente — una vez caducado el refresh, ya no se puede
-- usar de todos modos).
--
-- Notas:
--   - jti es UUID. Lo emite JwtService en cada createRefreshToken.
--   - user_id es informativo: facilita revocar todos los refreshes de
--     un usuario sin tener que recolectar sus jti.
--   - revoked_at se usa para el job de purga futura.
-- ===========================================================================

CREATE TABLE revoked_refresh_tokens (
    jti CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    revoked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_revoked_refresh_tokens PRIMARY KEY (jti),
    CONSTRAINT fk_revoked_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    INDEX ix_revoked_refresh_tokens_user (user_id),
    INDEX ix_revoked_refresh_tokens_revoked_at (revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
