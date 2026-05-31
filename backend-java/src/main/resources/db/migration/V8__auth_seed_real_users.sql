-- ===========================================================================
-- V8__auth_seed_real_users.sql
--
-- Habilita login real (email + password) sembrando dos usuarios demo
-- listos para usar, su empresa adicional, y las activaciones de modulos
-- correspondientes. Reemplaza al PIN como login principal: el PIN sigue
-- disponible para fichaje en kiosko y desbloqueo de pantalla (decision
-- 6 de project-benjagest-architecture).
--
-- Passwords demo: Benjamin123456$ (BCrypt, mismo hash para ambos).
-- El hash se genero con BCryptPasswordEncoder de Spring Security
-- (cost 10, salt aleatorio). Validado en BcryptHashGenerator.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) Set password del admin demo existente (asesoria, OWNER en BENJAGEST)
--    + display_name acorde al contexto.
-- ---------------------------------------------------------------------------
UPDATE user_accounts
   SET password_hash = '$2a$10$5sPW7L0Nqfepi4qova5c/u12CmrMg1sO82A5FRCDXHdd2/Wt.2tNy',
       display_name  = 'Benjamin Asesor'
 WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';

-- ---------------------------------------------------------------------------
-- 2) Empresa nueva tipo CLIENT para el segundo demo (empresario).
-- ---------------------------------------------------------------------------
INSERT INTO companies (id, legal_name, trade_name, tax_identifier, company_type, email, phone)
VALUES (
    '33333333-3333-3333-3333-333333333333',
    'Marcos Construcciones SL',
    'Marcos Construcciones',
    'B09990500',
    'CLIENT',
    'contacto@marcosconstrucciones.local',
    '910000500'
)
ON DUPLICATE KEY UPDATE legal_name = VALUES(legal_name);

-- Settings vacios para que la empresa funcione (el modelo lo espera).
INSERT INTO company_settings (company_id, enabled_modules, dashboard_widgets, mobile_modules, security_settings, ai_tokens)
VALUES (
    '33333333-3333-3333-3333-333333333333',
    JSON_ARRAY('customers','billing','purchases','tax','reports','settings'),
    JSON_ARRAY('revenue','pending','alerts'),
    JSON_ARRAY('pin','time_clock'),
    JSON_OBJECT('pinLogin', true, 'sessionMinutes', 480),
    0
)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;

-- ---------------------------------------------------------------------------
-- 3) Usuario empresario (Marcos Lopez, OWNER en su empresa).
-- ---------------------------------------------------------------------------
INSERT INTO user_accounts (id, email, password_hash, display_name, global_role, active)
VALUES (
    'cccccccc-cccc-cccc-cccc-cccccccccccc',
    'empresario@benjagest.local',
    '$2a$10$5sPW7L0Nqfepi4qova5c/u12CmrMg1sO82A5FRCDXHdd2/Wt.2tNy',
    'Marcos Lopez',
    'USER',
    TRUE
)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

INSERT INTO company_memberships (id, company_id, user_id, role_name)
VALUES (
    '30303030-3030-3030-3030-303030303030',
    '33333333-3333-3333-3333-333333333333',
    'cccccccc-cccc-cccc-cccc-cccccccccccc',
    'OWNER'
)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

-- ---------------------------------------------------------------------------
-- 4) Activar modulos para Marcos Construcciones SL.
--    Kit minimo para un empresario: core, billing, purchases, accounting,
--    tax, calendar, notifications + sus sub-modulos. Sin advisory, kiosk,
--    labor, time-clock, self-employed, documents, reports.
-- ---------------------------------------------------------------------------
INSERT INTO company_modules (id, company_id, module_id, active)
SELECT UUID(),
       '33333333-3333-3333-3333-333333333333',
       m.id,
       TRUE
  FROM module_catalog m
  LEFT JOIN module_catalog p ON p.id = m.parent_id
 WHERE (m.slug IN ('core', 'billing', 'purchases', 'accounting', 'tax', 'calendar', 'notifications')
        OR p.slug IN ('core', 'billing', 'purchases', 'accounting', 'tax', 'calendar', 'notifications'))
   AND NOT EXISTS (
       SELECT 1 FROM company_modules cm
        WHERE cm.company_id = '33333333-3333-3333-3333-333333333333'
          AND cm.module_id = m.id
   );
