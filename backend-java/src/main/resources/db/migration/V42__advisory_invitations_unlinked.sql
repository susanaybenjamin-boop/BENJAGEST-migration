-- ===========================================================================
-- V42__advisory_invitations_unlinked.sql
--
-- UNLINK-SYNC (2026-06-05): cuando el empresario rompe el vínculo con su
-- asesoría, hasta ahora la invitación ACCEPTED se quedaba ACCEPTED para
-- siempre y la pantalla "Invitaciones" de la asesoría mostraba el
-- cliente como "Aceptada" aunque ya estuviera desvinculado. Esto añade:
--
--   1) Nuevo estado terminal 'UNLINKED' en el CHECK constraint.
--   2) Columna unlinked_at para auditar cuándo se rompió el vínculo.
--
-- La invitación se conserva (auditabilidad / posible reinvitación con
-- un click), el customer auto-creado se conserva también (puede tener
-- facturas emitidas), y la UI distingue claramente este caso con badge.
-- ===========================================================================

-- Ampliar el CHECK del status para aceptar UNLINKED.
-- En MariaDB el CHECK constraint hay que reemplazarlo (DROP + ADD).
-- Idempotente: solo lo hacemos si aún no contiene 'UNLINKED'.
SET @needs_update := (
    SELECT COUNT(*)
      FROM information_schema.CHECK_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE()
       AND CONSTRAINT_NAME = 'ck_advisory_invitations_status'
       AND CHECK_CLAUSE NOT LIKE '%UNLINKED%'
);

SET @sql := IF(@needs_update > 0,
    'ALTER TABLE advisory_invitations DROP CONSTRAINT ck_advisory_invitations_status',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@needs_update > 0,
    'ALTER TABLE advisory_invitations ADD CONSTRAINT ck_advisory_invitations_status CHECK (status IN (''PENDING'', ''ACCEPTED'', ''REJECTED'', ''EXPIRED'', ''REVOKED'', ''UNLINKED''))',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Añadir columna unlinked_at si no existe (idempotente).
SET @col_exists := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'advisory_invitations'
       AND COLUMN_NAME = 'unlinked_at'
);

SET @sql := IF(@col_exists = 0,
    'ALTER TABLE advisory_invitations ADD COLUMN unlinked_at TIMESTAMP NULL AFTER accepted_at',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
