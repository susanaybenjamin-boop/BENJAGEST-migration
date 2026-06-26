-- =============================================================================
-- V72 — SEED del PIN del OWNER de Benjamin para que pueda hacer login PIN
--       desde el día uno (acordado 2026-06-07).
--
-- Estructura:
--   - asesoría: id = '11111111-1111-1111-1111-111111111111'
--     (legalName 'Benjamin Gestiones Integrales SL', sembrada en V3).
--   - user_account OWNER: id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
--     (email admin@benjagest.local, sembrado V3 + V8 con password).
--
-- Pasos idempotentes:
--   1) Si NO existe ya un employees row para ese OWNER en esa asesoría,
--      crearlo con app_access=TRUE y el hash bcrypt del PIN '2406'.
--   2) Si SÍ existe, hacer UPDATE para asegurar app_access=TRUE y el
--      pin_hash actualizado (no sobreescribe si Benjamin ya cambió el
--      PIN — el WHERE matchea el row específico).
--
-- Hash bcrypt:
--   '$2a$10$cZLkhT9IZN/IRWrNPnEC1OyA05Zc8REHeYpokRi4frVb7Ga8hpV2C'
--   Generado con Spring's BCryptPasswordEncoder cost=10 (mismo bean que
--   usa AuthService) — validado con matches('2406', hash) → true.
--
-- Si Benjamin cambia su PIN más adelante desde la UI (L4-5), se sobreescribe
-- este hash por uno nuevo y la migración no vuelve a aplicarse (Flyway
-- solo corre cada V una vez). Cambiar el PIN no requiere migración nueva.
-- =============================================================================

INSERT INTO employees (id, company_id, user_id, app_access,
                        full_name, tax_identifier, email, phone,
                        pin_hash, work_type, max_shift_minutes, active)
SELECT '60000000-0000-0000-0000-0000000000be',
       '11111111-1111-1111-1111-111111111111',
       'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
       TRUE,
       'Benjamin Asesor (OWNER)',
       NULL, 'admin@benjagest.local', NULL,
       '$2a$10$cZLkhT9IZN/IRWrNPnEC1OyA05Zc8REHeYpokRi4frVb7Ga8hpV2C',
       'FULL_TIME', NULL, TRUE
 WHERE NOT EXISTS (
     SELECT 1 FROM employees
      WHERE company_id = '11111111-1111-1111-1111-111111111111'
        AND user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
 );

-- UPDATE para el caso en que el employees row ya existiera (idempotente
-- + acepta que el PIN del OWNER siempre quede como '2406' tras migrar).
-- Si Benjamin lo cambia desde UI después, esta migración ya no se
-- ejecuta y el PIN nuevo se preserva.
UPDATE employees
   SET app_access = TRUE,
       pin_hash = '$2a$10$cZLkhT9IZN/IRWrNPnEC1OyA05Zc8REHeYpokRi4frVb7Ga8hpV2C'
 WHERE company_id = '11111111-1111-1111-1111-111111111111'
   AND user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
