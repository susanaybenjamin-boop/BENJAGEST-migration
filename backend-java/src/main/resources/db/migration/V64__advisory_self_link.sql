-- ===========================================================================
-- V64__advisory_self_link.sql  (2026-06-07)
--
-- AUTOVINCULACIÓN SILENCIOSA DE LA ASESORÍA (Slice 3G)
--
-- La asesoría también es una empresa con su NIF y necesita llevar su propia
-- contabilidad (Diario, Mayor, Asientos, Recurrentes, Modelos AEAT). El
-- diseño que tenemos hasta ahora SOLO daba acceso a la asesoría a las
-- empresas de SUS CLIENTES (vinculados o no), no a sí misma.
--
-- Para que la asesoría pueda gestionarse a SÍ MISMA reutilizando todo el
-- código de "cliente vinculado" sin duplicar pantallas/sidebar, insertamos
-- una fila advisory_invitations con status='ACCEPTED' entre cada asesoría
-- y sí misma. Esto la hace aparecer como un cliente vinculado "de fábrica"
-- desde el punto de vista de los queries de portfolio.
--
-- Diseño:
--   advisory_company_id = self
--   invited_company_id  = self  (← el truco)
--   invited_nif         = self.tax_identifier
--   token               = "SELF:<advisory_id>"  (determinista, único)
--   status              = 'ACCEPTED'
--   expires_at          = año 2999 (no expira nunca)
--   accepted_at         = NOW
--
-- La UI filtrará esta fila del listado "Mis clientes" y la mostrará en
-- un acceso aparte ("Mi empresa" en el sidebar — Slice 3G-2).
--
-- Idempotente: usa INSERT IGNORE para que volver a aplicar la migración o
-- correr el hook de boot no duplique filas.
-- ===========================================================================

INSERT IGNORE INTO advisory_invitations (
    id,
    advisory_company_id,
    invited_email,
    invited_nif,
    invited_company_name,
    invited_company_id,
    token,
    status,
    expires_at,
    notes,
    accepted_at,
    created_at,
    updated_at
)
SELECT
    UUID(),
    c.id,
    NULL,
    c.tax_identifier,
    COALESCE(c.trade_name, 'Mi empresa'),
    c.id,
    CONCAT('SELF:', c.id),
    'ACCEPTED',
    '2999-12-31 23:59:59',
    'Auto-vinculación silenciosa de la asesoría a sí misma (V64). NO BORRAR.',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM companies c
WHERE c.company_type = 'ADVISORY'
  AND NOT EXISTS (
      SELECT 1
        FROM advisory_invitations ai
       WHERE ai.advisory_company_id = c.id
         AND ai.invited_company_id  = c.id
         AND ai.status = 'ACCEPTED'
  );
