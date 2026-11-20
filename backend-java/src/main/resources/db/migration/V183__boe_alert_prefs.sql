-- =============================================================================
-- V183__boe_alert_prefs.sql
--
-- BOE-FILTRO (2026-11-20). Benjamin: "cuando llegan del BOE notificaciones, el
-- filtro es light, y se llena de noticias que no interesan, tendriamos que
-- poder escoger que queremos que nos llegue".
--
-- Hasta hoy el filtro era una constante `static final` en BoeAlertService,
-- igual para todo el mundo, y `notifyAdvisories()` mandaba el aviso a TODAS
-- las asesorias. No habia forma de elegir nada.
--
-- Medido contra el sumario REAL del BOE del 19-nov-2026 (104 articulos):
-- el filtro de hoy generaba 13 avisos y solo UNO servia (el RD del salario
-- minimo para 2027). Los otros 12:
--   * 6 por la palabra "IVA" comparada con contains() SIN limites de palabra,
--     que engancha dentro de "Privada", "selectivas", "educativa", "negativa"
--     y "preventiva" — ni uno hablaba de IVA.
--   * 5 por el comodin "Real Decreto" (subvenciones a una fundacion, servicio
--     universal, una Gran Cruz a titulo postumo...).
--   * 2 por "Seguridad Social" dentro de nombramientos de personal.
--
-- Esta migracion es ADITIVA: una tabla nueva de preferencias por empresa y dos
-- columnas nuevas en `boe_alerts`. No toca ni borra nada existente, y sin fila
-- de preferencias el servicio usa los valores de fabrica (fail-open), asi que
-- quien no configure nada sigue recibiendo avisos.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) Preferencias por empresa.
--
--    Listas guardadas como texto con un elemento POR LINEA. Se eligio frente a
--    tablas hijas porque son listas cortas que la UI edita y guarda enteras de
--    una vez; no se consultan por elemento ni se ordenan por ellas.
--
--    NULL y cadena vacia NO significan lo mismo:
--      * NULL  = "no lo he configurado" -> se usa el valor de fabrica.
--      * vacio = "no quiero filtrar por esto" -> pasa todo.
--    Por eso las columnas admiten NULL y no llevan DEFAULT.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS boe_alert_prefs (
    company_id   CHAR(36)   NOT NULL,
    -- Interruptor general: FALSE = esta empresa no quiere avisos del BOE.
    enabled      BOOLEAN    NOT NULL DEFAULT TRUE,
    -- Codigos de seccion del BOE separados por coma ("1,3"). Las secciones
    -- "II. Autoridades y personal" (2A nombramientos, 2B oposiciones) son las
    -- que mas ruido meten en una asesoria, y hasta hoy ni se leian.
    sections     VARCHAR(80) NULL,
    -- Nombres de ministerio tal como los publica el BOE, uno por linea.
    departments  TEXT       NULL,
    -- Palabras clave, una por linea.
    keywords     TEXT       NULL,
    updated_at   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (company_id),
    CONSTRAINT fk_boe_alert_prefs_company
        FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 2) `boe_alerts` guarda ademas la seccion.
--
--    El departamento ya se guardaba desde la V103 y NO se usaba para filtrar;
--    la seccion ni siquiera se leia del JSON. Las dos son justo las palancas
--    que quitan el ruido sin tocar una sola palabra clave.
--
--    Idempotente via information_schema (mismo patron que V40 / V45).
-- -----------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'boe_alerts'
              AND COLUMN_NAME = 'section_code');
SET @sql := IF(@c = 0,
    'ALTER TABLE boe_alerts ADD COLUMN section_code VARCHAR(10) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'boe_alerts'
              AND COLUMN_NAME = 'section_name');
SET @sql := IF(@c = 0,
    'ALTER TABLE boe_alerts ADD COLUMN section_name VARCHAR(160) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
