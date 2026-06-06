-- V61 — Fix del DEFAULT obsoleto de companies.verifactu_mode
--
-- Trampa heredada de la evolución de migraciones:
--
--   V13 → ADD COLUMN verifactu_mode VARCHAR(10) NOT NULL DEFAULT 'OFF'
--         CHECK (verifactu_mode IN ('OFF', 'TEST', 'PROD'))
--
--   V17 → DROP CONSTRAINT ck_companies_verifactu_mode
--         ADD CONSTRAINT ck_companies_verifactu_mode CHECK
--             (verifactu_mode IN ('TEST', 'PROD'))
--
-- V17 cambió el CHECK pero NO actualizó el DEFAULT. Resultado: cualquier
-- INSERT en companies que omita verifactu_mode falla porque MariaDB usa
-- el DEFAULT 'OFF' y dispara la check constraint.
--
-- El bug se hace visible en V60+ con el endpoint start-management, que
-- crea shadow companies para clientes no vinculados.
--
-- Fix: alinear el DEFAULT con el CHECK. 'TEST' es la opción segura — la
-- modalidad legal va aparte en verifactu_modality, y TEST nunca envía a
-- producción.
--
-- Idempotente: usamos MODIFY COLUMN que es no-op si el default ya está.

ALTER TABLE companies
    MODIFY COLUMN verifactu_mode VARCHAR(10) NOT NULL DEFAULT 'TEST';
