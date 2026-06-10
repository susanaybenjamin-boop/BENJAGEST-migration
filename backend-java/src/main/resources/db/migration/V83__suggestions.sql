-- =============================================================================
-- V83 — Modulo "Sugerencias" (buzon de feedback hacia el fabricante).
--
-- PORT-3 SUG: el usuario (OWNER/ADMIN/ACCOUNTANT/EMPLOYEE) puede enviar
-- una sugerencia/bug/mejora; queda guardada en su empresa y visible solo
-- para ella. El fabricante (rol global futuro) podra consultarlas todas
-- y responder. Por ahora la lectura es per-empresa.
--
-- Tabla `suggestions` reemplaza la `sugerencias_180` de CONTENDO con
-- nombres en ingles para coherencia con el resto del schema BENJAGEST.
-- =============================================================================

CREATE TABLE IF NOT EXISTS suggestions (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    created_by_user_id CHAR(36) NULL,
    title VARCHAR(180) NOT NULL,
    description TEXT NOT NULL,
    -- general / improvement / module / bug / other
    category VARCHAR(40) NOT NULL DEFAULT 'general',
    -- new / read / answered / closed
    status VARCHAR(20) NOT NULL DEFAULT 'new',
    answer TEXT NULL,
    answered_at TIMESTAMP NULL,
    answered_by_user_id CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_suggestions PRIMARY KEY (id),
    CONSTRAINT fk_suggestions_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_suggestions_company_status (company_id, status),
    INDEX ix_suggestions_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Registrar el modulo en el catalogo + activar para TODAS las empresas
-- (cualquier empresa puede sugerir mejoras).
INSERT INTO module_catalog
       (id, slug, label, description, parent_id, icon,
        display_order, advisory_only)
VALUES (UUID(), 'suggestions', 'Sugerencias',
        'Buzon de sugerencias, mejoras y reporte de bugs hacia el fabricante de BENJAGEST. El equipo de soporte responde desde el modulo fabricante.',
        NULL, 'fas-lightbulb', 880, FALSE)
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO company_modules (id, company_id, module_id, active,
                              activated_at, activated_by)
SELECT UUID(), c.id, m.id, TRUE, CURRENT_TIMESTAMP, NULL
  FROM companies c
  CROSS JOIN (SELECT id FROM module_catalog WHERE slug = 'suggestions') m
 WHERE 1 = 1
ON DUPLICATE KEY UPDATE active = TRUE,
                        deactivated_at = NULL,
                        deactivated_by = NULL;
