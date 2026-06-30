-- V160 — Ancla el NIF en las invitaciones ya ACEPTADAS (bug vínculo-por-email
-- 2026-06-30).
--
-- Problema: el portfolio de la asesoría detectaba el vínculo cruzando las
-- invitaciones por NIF *o* email. Al cambiar el email de un cliente al de otra
-- empresa vinculada que comparte correo, la invitación ACEPTADA de esa otra
-- empresa casaba por email y el cliente aparecía como vinculado (fuga de datos).
--
-- A partir de ahora (AdvisoryInvitationRepository.updateStatusAccepted) toda
-- aceptación ancla invited_nif al NIF de la empresa que acepta, y el portfolio
-- solo cruza por email cuando la invitación NO tiene NIF. Esta migración hace el
-- backfill defensivo de las aceptadas anteriores que quedaron sin invited_nif,
-- tomándolo de la empresa que aceptó (invited_company_id). Idempotente: solo
-- toca las que aún no lo tienen.
-- COLLATE explícito en la comparación de ids: en MariaDB 11.4 las columnas
-- pueden tener collations distintas (ver reference FK collation) y un JOIN sin
-- COLLATE daría "Illegal mix of collations" → Flyway falla y el backend no
-- arranca. Forzamos utf8mb4_unicode_ci (estándar del proyecto) en un operando.
UPDATE advisory_invitations ai
   JOIN companies c ON c.id = ai.invited_company_id COLLATE utf8mb4_unicode_ci
   SET ai.invited_nif = c.tax_identifier
 WHERE ai.status = 'ACCEPTED'
   AND (ai.invited_nif IS NULL OR ai.invited_nif = '')
   AND ai.invited_company_id IS NOT NULL
   AND c.tax_identifier IS NOT NULL
   AND c.tax_identifier <> '';
