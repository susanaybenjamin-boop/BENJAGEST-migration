-- ===========================================================================
-- V35__time_clock_event_types.sql
--
-- TC-CFG (2026-06-04): tipos de evento de fichaje configurables por empresa.
--
-- Hasta ahora los tipos eran un enum hardcoded en codigo y UI:
--   IN, OUT, BREAK_START, BREAK_END.
--
-- Las apps del sector (Sesame, Factorial, Bizneo, JornAda) permiten al
-- empresario definir SUS tipos: pausa para comida, pausa cafe, reunion
-- externa, formacion, teletrabajo... cada uno con icono y un flag de si
-- "cuenta como trabajo" para el computo de horas.
--
-- Modelo:
--
--   time_clock_event_types: catalogo por empresa.
--     - code: identificador corto (en mayusculas). Usado por la app al
--       fichar (POST /punch envia code).
--     - label_es / label_en: nombre visible.
--     - icon: FontAwesome code (fas-sign-in-alt, fas-coffee, etc).
--     - display_order: orden en la fila de botones.
--     - is_work_time: TRUE para eventos que "abren" tiempo trabajado
--       (IN, vuelta de pausa). FALSE para los que "cierran" (OUT,
--       pausa, baja).
--     - is_pause: TRUE si es una pausa que parara la cuenta de horas
--       pero el empleado sigue en la jornada. FALSE para entradas y
--       salidas reales.
--     - active: para desactivar tipos sin perder historico.
--
-- Seed inicial para cada empresa: los 4 originales (compatibilidad).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS time_clock_event_types (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    label_es VARCHAR(120) NOT NULL,
    label_en VARCHAR(120) NOT NULL,
    icon VARCHAR(60) NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_work_time BOOLEAN NOT NULL DEFAULT TRUE,
    is_pause BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_time_clock_event_types PRIMARY KEY (id),
    CONSTRAINT uk_time_clock_event_types_code UNIQUE (company_id, code),
    CONSTRAINT fk_time_clock_event_types_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_time_clock_event_types_company (company_id, active, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed: para cada empresa existente, los 4 tipos originales.
-- (Para empresas nuevas, el TimeClockEventTypeService se encarga al
-- primer acceso, ver TimeClockEventTypeService.ensureSeedFor()).
INSERT INTO time_clock_event_types
    (id, company_id, code, label_es, label_en, icon, display_order, is_work_time, is_pause)
SELECT UUID(), c.id, 'IN', 'Entrada', 'Clock in', 'fas-sign-in-alt', 1, TRUE, FALSE
  FROM companies c
 WHERE NOT EXISTS (
       SELECT 1 FROM time_clock_event_types t
        WHERE t.company_id = c.id AND t.code = 'IN');

INSERT INTO time_clock_event_types
    (id, company_id, code, label_es, label_en, icon, display_order, is_work_time, is_pause)
SELECT UUID(), c.id, 'OUT', 'Salida', 'Clock out', 'fas-sign-out-alt', 2, FALSE, FALSE
  FROM companies c
 WHERE NOT EXISTS (
       SELECT 1 FROM time_clock_event_types t
        WHERE t.company_id = c.id AND t.code = 'OUT');

INSERT INTO time_clock_event_types
    (id, company_id, code, label_es, label_en, icon, display_order, is_work_time, is_pause)
SELECT UUID(), c.id, 'BREAK_START', 'Inicio pausa', 'Break start', 'fas-coffee', 3, FALSE, TRUE
  FROM companies c
 WHERE NOT EXISTS (
       SELECT 1 FROM time_clock_event_types t
        WHERE t.company_id = c.id AND t.code = 'BREAK_START');

INSERT INTO time_clock_event_types
    (id, company_id, code, label_es, label_en, icon, display_order, is_work_time, is_pause)
SELECT UUID(), c.id, 'BREAK_END', 'Fin pausa', 'Break end', 'fas-utensils', 4, TRUE, TRUE
  FROM companies c
 WHERE NOT EXISTS (
       SELECT 1 FROM time_clock_event_types t
        WHERE t.company_id = c.id AND t.code = 'BREAK_END');
