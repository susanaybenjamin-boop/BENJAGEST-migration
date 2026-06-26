-- ===========================================================================
-- V148 — Purga de datos DEMO para arranque LIMPIO (decisión Benjamin 2026-06-26).
--
-- Las migraciones V3/V8 (y datos asociados en V4/V6/V7) siembran empresas y
-- usuarios de PRUEBA. En lugar de neutralizar esos seeds —que rompía la cadena
-- por dependencias (V4 siembra el PGC de la empresa demo)— se DEJAN sembrar y se
-- BORRAN aquí, al final. Así las migraciones funcionan y la BD queda limpia: sin
-- cuentas → el primer arranque muestra el REGISTRO en vez del login.
--
-- SEGURO para datos reales:
--   · Los usuarios demo usan dominio reservado «.local»; los reales usan dominios
--     reales (gmail.com, etc.) y se crean TRAS el arranque, nunca durante la
--     migración → este DELETE jamás los alcanza.
--   · Las empresas demo son dos UUIDs fijos (11111111…/22222222…) o tienen email
--     «.local»; las reales se crean con UUID aleatorio y email real.
--
-- Se borran cuentas/empresas/memberships (lo que controla login y visibilidad).
-- El rastro hijo (facturas/cuentas demo) queda huérfano por company_id pero es
-- INVISIBLE (sin empresa ni usuario). NO se enumeran tablas hijas a propósito,
-- para no arriesgar un error de columna que bloquee el arranque.
-- ===========================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- Memberships de usuarios demo y de las empresas demo.
DELETE FROM company_memberships
 WHERE user_id IN (SELECT id FROM user_accounts WHERE email LIKE '%.local');
DELETE FROM company_memberships
 WHERE company_id IN ('11111111-1111-1111-1111-111111111111',
                      '22222222-2222-2222-2222-222222222222');

-- Usuarios demo.
DELETE FROM user_accounts WHERE email LIKE '%.local';

-- Empresas demo.
DELETE FROM companies
 WHERE id IN ('11111111-1111-1111-1111-111111111111',
              '22222222-2222-2222-2222-222222222222')
    OR email LIKE '%.local';

SET FOREIGN_KEY_CHECKS = 1;
