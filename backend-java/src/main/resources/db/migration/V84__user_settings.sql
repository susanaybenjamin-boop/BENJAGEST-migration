-- =============================================================================
-- V84 — Preferencias por usuario + modulo "profile".
--
-- PORT-3 PERFIL + LOCK: cada user_account tiene una fila aqui con sus
-- preferencias personales. Por defecto, no existe la fila — el service
-- la crea con valores por defecto la primera vez que se consulta o
-- guarda.
--
-- Campos:
--   - language          → ES | EN (por defecto la del JWT al crear).
--   - pin_timeout_min   → minutos de inactividad antes de bloquear con
--                          PIN. 0 = desactivado.
--   - screensaver_style → reserved para futuros estilos visuales
--                          (clock / logo / dark) — solo se persiste.
--   - ai_enabled        → reserved para AI Copilot (BAJA del backlog).
--   - avatar_path       → ruta local al PNG del avatar (no se sube al
--                          server, se almacena solo la ruta).
--   - workday_template  → reserved para PORT-2 (jornadas).
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_settings (
    user_id CHAR(36) NOT NULL,
    language VARCHAR(5) NOT NULL DEFAULT 'es',
    pin_timeout_min INT NOT NULL DEFAULT 0,
    screensaver_style VARCHAR(40) NOT NULL DEFAULT 'clock',
    ai_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    avatar_path VARCHAR(500) NULL,
    workday_template VARCHAR(80) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_settings PRIMARY KEY (user_id),
    CONSTRAINT fk_user_settings_user FOREIGN KEY (user_id) REFERENCES user_accounts (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Modulo "profile" visible en cualquier empresa.
INSERT INTO module_catalog
       (id, slug, label, description, parent_id, icon,
        display_order, advisory_only)
VALUES (UUID(), 'profile', 'Mi perfil',
        'Preferencias personales del usuario logueado: idioma, avatar, bloqueo por inactividad y otros ajustes individuales.',
        NULL, 'fas-user-circle', 870, FALSE)
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO company_modules (id, company_id, module_id, active,
                              activated_at, activated_by)
SELECT UUID(), c.id, m.id, TRUE, CURRENT_TIMESTAMP, NULL
  FROM companies c
  CROSS JOIN (SELECT id FROM module_catalog WHERE slug = 'profile') m
 WHERE 1 = 1
ON DUPLICATE KEY UPDATE active = TRUE,
                        deactivated_at = NULL,
                        deactivated_by = NULL;
