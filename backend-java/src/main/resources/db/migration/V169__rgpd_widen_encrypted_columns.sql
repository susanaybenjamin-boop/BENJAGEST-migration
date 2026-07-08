-- V169 (bloque RGPD, 2026-07-08): ensanchar las columnas que pasan a
-- guardarse CIFRADAS (FieldCipher, prefijo ENC:). El texto cifrado de
-- Jasypt (salt + IV + AES-256, base64) es ~3-4x el original mas el
-- prefijo, asi que un IBAN de 24 chars ya no cabe en VARCHAR(34).
--
-- - employees.iban / customers.iban: VARCHAR(34) -> VARCHAR(255).
-- - employee_leave_requests.reason (motivo, puede llevar dato medico):
--   VARCHAR(240) -> TEXT (240 chars en claro no caben cifrados).
-- - medical_leaves.notes ya es TEXT (no se toca).
--
-- Idempotente por naturaleza (MODIFY del mismo tipo destino).

ALTER TABLE employees
    MODIFY iban VARCHAR(255) NULL;

ALTER TABLE customers
    MODIFY iban VARCHAR(255) NULL;

ALTER TABLE employee_leave_requests
    MODIFY reason TEXT NULL;
