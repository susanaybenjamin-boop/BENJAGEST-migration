-- =============================================================================
-- V90 — Alinear schema de `work_logs` con el modelo embebido CONTENDO
-- (PORT-2 decisión Benjamin 2026-06-10).
--
-- ORIGEN DEL BUG:
--   V2 (schema inicial) ya creó `work_logs` con columnas
--   work_date, minutes, value_amount, payment_status, work_item_id, etc.
--   V86 intentó `CREATE TABLE IF NOT EXISTS work_logs` con el modelo
--   nuevo (log_date, minutes_worked, is_billable, billable_amount, etc.)
--   pero como la tabla YA existía, el IF NOT EXISTS la saltó. El backend
--   queda mirando a una tabla con columnas que no tiene → "bad SQL grammar"
--   al abrir la pestaña Partes/jornadas.
--
-- FIX:
--   - ADD COLUMN IF NOT EXISTS las columnas que faltan (aditivo).
--   - UPDATE para sincronizar registros viejos: log_date ← work_date,
--     minutes_worked ← minutes, billable_amount ← value_amount.
--   - Mantenemos las columnas viejas vivas porque WorkspaceRepository.labor()
--     (dashboard genérico antiguo) las sigue leyendo. Su drop queda como
--     deuda futura cuando se refactorice el dashboard.
--
-- Lección 2026-06-10: confirmar el schema REAL en BD con grep de
-- migraciones previas antes de asumir que CREATE TABLE va a triunfar.
-- (Ver CLAUDE.md sección 10.bis.)
-- =============================================================================

-- Columnas que V86 quería crear pero ya existían en V2 NO se tocan
-- (id, company_id, employee_id, customer_id, description, created_at,
-- updated_at, pk, fks).

-- Columnas NUEVAS que V86 quería añadir y la tabla NO tenía:
ALTER TABLE work_logs
    ADD COLUMN IF NOT EXISTS log_date DATE NULL,
    ADD COLUMN IF NOT EXISTS minutes_worked INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS is_billable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS billable_amount DECIMAL(12,2) NULL,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS billed_invoice_line_id CHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS approved_by_user_id CHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS notes TEXT NULL;

-- Sincronizar registros viejos (si los hay) para que sean visibles
-- desde el SELECT nuevo. work_date y minutes existen desde V2.
UPDATE work_logs
   SET log_date = work_date
 WHERE log_date IS NULL AND work_date IS NOT NULL;

UPDATE work_logs
   SET minutes_worked = minutes
 WHERE minutes_worked = 0 AND minutes IS NOT NULL AND minutes > 0;

UPDATE work_logs
   SET billable_amount = value_amount
 WHERE billable_amount IS NULL AND value_amount IS NOT NULL;

-- Ahora `log_date` puede ser NOT NULL salvo en filas vacías —
-- pero por seguridad lo dejamos NULL nullable; el SELECT con
-- BETWEEN ignora los NULL automáticamente.

-- Índice por log_date para que el SELECT del WorkLogService no haga
-- full scan. CREATE INDEX IF NOT EXISTS no es estándar en MariaDB 11.4
-- pero ADD INDEX dentro de ALTER TABLE permite usar IF NOT EXISTS desde
-- 10.0.2.
ALTER TABLE work_logs
    ADD INDEX IF NOT EXISTS ix_work_logs_log_date (company_id, log_date);
