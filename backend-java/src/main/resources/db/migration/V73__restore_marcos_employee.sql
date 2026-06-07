-- =============================================================================
-- V73 — Restaurar la fila employees de Marcos Encargado.
--
-- Contexto:
--   V3 sembró user_accounts (bbbbbbbb-...) + company_memberships +
--   employees para Marcos en la asesoría de Benjamin. Entre V3 y hoy
--   la fila de employees se perdió (test, reset, cleanup PURCHASES…),
--   pero su user_account y membership siguen vivos. El resultado es
--   que Marcos aparece en el módulo Equipo (que mira memberships) y
--   NO aparece en Laboral (que mira employees) — inconsistencia.
--
-- Restauración:
--   - Recreamos su fila employees con los mismos datos que V3 + los
--     campos nuevos de V70 (app_access=FALSE — sirve de ejemplo
--     "empleado con contrato pero sin acceso a la app" para validar
--     el filtrado del módulo Equipo en L4-5).
--   - pin_hash = NULL: el legacy SHA256 quedó anulado por V70; el
--     OWNER le asignará un PIN bcrypt desde la UI si quiere darle
--     acceso (L4-4 cierra ese flujo).
--   - Idempotente: NOT EXISTS por el par (company_id, user_id) +
--     UPDATE complementario para asegurar el estado correcto si la
--     fila ya hubiera sido restaurada manualmente.
-- =============================================================================

INSERT INTO employees (id, company_id, user_id, app_access,
                        default_customer_id,
                        full_name, tax_identifier, email, phone,
                        pin_hash, work_type, max_shift_minutes, active)
SELECT '60000000-0000-0000-0000-000000000001',
       '11111111-1111-1111-1111-111111111111',
       'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
       FALSE,
       '30000000-0000-0000-0000-000000000001',
       'Marcos Encargado',
       '12345678Z', 'marcos@benjagest.local', '640000001',
       NULL, 'FULL_TIME', 600, TRUE
 WHERE NOT EXISTS (
     SELECT 1 FROM employees
      WHERE company_id = '11111111-1111-1111-1111-111111111111'
        AND user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
 );

UPDATE employees
   SET active = TRUE,
       full_name = 'Marcos Encargado',
       app_access = FALSE
 WHERE company_id = '11111111-1111-1111-1111-111111111111'
   AND user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
