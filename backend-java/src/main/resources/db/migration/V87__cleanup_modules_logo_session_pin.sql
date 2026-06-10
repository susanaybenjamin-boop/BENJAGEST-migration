-- =============================================================================
-- V87 — Limpieza tras revisión Benjamin 2026-06-10 (sesión tarde):
--
-- DECISIONES:
--   1) Mi perfil (slug 'profile') no aporta nada — el idioma ya vive en el
--      header (botón ES/EN), el AI Copilot no se hará en BENJAGEST, y el
--      avatar pasa a ser el logo de empresa. El bloqueo por inactividad
--      se mueve a Configuración → Sesión. Eliminamos el módulo.
--   2) Portal del empleado (slug 'employee-portal') NO está pensado para
--      el cliente JavaFX desktop — su sitio será una app móvil/tablet
--      futura. Eliminamos del catálogo (el backend /api/portal/* se
--      conserva intacto para ese día).
--   3) Jornadas y partes (slug 'shifts') NO va como módulo de raíz —
--      será una pestaña dentro del módulo Labor. Eliminamos del catálogo
--      (el backend /api/work-logs/* se conserva, lo lee la sub-pestaña).
--   4) Logo empresa: nueva columna companies.logo_path. Se guarda en
--      {invoice_storage_root}/{companyId}/_brand/logo.png. Se aplica
--      en PDF factura (el placeholder ya existe en InvoicePdfGenerator).
--   5) PIN de sesión (para el lock screen) es DISTINTO del PIN de
--      vinculación (device_tokens.pin_hash). Se almacena en
--      user_accounts.session_pin_hash y se usa solo para desbloquear
--      tras timeout. En modo asesoría puede reusarse el PIN de
--      vinculación si así lo elige el usuario.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- (1)(2)(3) Eliminar los 3 módulos del catálogo.
--           ON DELETE CASCADE en company_modules limpia las activaciones.
-- ---------------------------------------------------------------------------
DELETE FROM company_modules
 WHERE module_id IN (SELECT id FROM module_catalog
                      WHERE slug IN ('profile', 'employee-portal', 'shifts'));

DELETE FROM module_catalog
 WHERE slug IN ('profile', 'employee-portal', 'shifts');

-- ---------------------------------------------------------------------------
-- (4) Logo empresa: path en disco al PNG/JPG.
--      Aditivo y nullable — empresas existentes con NULL hasta subir uno.
--      IF NOT EXISTS: V87 ya pudo ejecutarse a medias en un intento
--      anterior antes de fallar en la siguiente clausula. ALTER en
--      MariaDB no es transaccional, por eso queda persistido.
-- ---------------------------------------------------------------------------
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS logo_path VARCHAR(500) NULL AFTER website;

-- ---------------------------------------------------------------------------
-- (5) PIN de sesión: hash bcrypt 60 chars. Separado del PIN de
--     vinculación (que vive en EMPLOYEES.pin_hash desde V3/V70 — NO en
--     user_accounts como asumí inicialmente). Nullable porque hay
--     usuarios que no usarán bloqueo (timeout=0).
--     Sin AFTER porque user_accounts no tiene pin_hash; la columna
--     queda al final, que es lo normal en MariaDB.
-- ---------------------------------------------------------------------------
ALTER TABLE user_accounts
    ADD COLUMN IF NOT EXISTS session_pin_hash VARCHAR(255) NULL;

-- ---------------------------------------------------------------------------
-- (6) Limpieza de user_settings: las preferencias que nos quedan son
--      pin_timeout_min y screensaver_style. El resto se va.
--      Idioma → header (atributo en user_accounts no necesario, el botón
--               cambia el state local).
--      Avatar → logo empresa.
--      AI Copilot → no se hará.
--      workday_template → no aplica (PORT-2 lleva otra ruta).
-- ---------------------------------------------------------------------------
ALTER TABLE user_settings
    DROP COLUMN IF EXISTS language,
    DROP COLUMN IF EXISTS ai_enabled,
    DROP COLUMN IF EXISTS avatar_path,
    DROP COLUMN IF EXISTS workday_template;
