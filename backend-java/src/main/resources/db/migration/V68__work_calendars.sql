-- =============================================================================
-- V68 — Calendario laboral por empresa con festivos detallados.
--
-- Sustento legal:
--   - Real Decreto Legislativo 2/2015 (Estatuto de los Trabajadores) art. 37.2:
--     hasta 14 días festivos al año.
--   - Resolución anual de la Dirección General de Trabajo del Ministerio
--     de Trabajo: festivos nacionales del año (publicada en BOE).
--   - Resolución autonómica equivalente: festivos por CCAA.
--   - Convenio colectivo / pleno municipal: festivos locales.
--
-- Modelo:
--   - work_calendars: un calendario activo POR (empresa, año). Si la empresa
--     opera en varios municipios usaría una fila por municipio en el futuro,
--     pero v1 mantenemos UK (company_id, year) — el calendario es del centro
--     de trabajo principal.
--   - holidays: cada festivo concreto con su ámbito (NATIONAL / CCAA / LOCAL),
--     fecha, nombre y si es retribuido. notes para casos especiales (festivo
--     trasladado, no recuperable, etc.).
-- =============================================================================

CREATE TABLE work_calendars (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    year INT NOT NULL,
    -- Código ISO 3166-2:ES sin el prefijo "ES-" (MD, CT, AN, VC, GA, PV, …).
    -- Sirve para que el importador desde plantilla BOE sepa qué festivos
    -- autonómicos aplicar.
    region_ccaa VARCHAR(2) NULL,
    -- Nombre del municipio para festivos locales (texto libre — no normalizamos
    -- contra INE en v1 porque el caso de uso son <14 festivos al año).
    region_municipality VARCHAR(120) NULL,
    name VARCHAR(200) NOT NULL,
    -- Activo: la empresa puede tener calendarios de años anteriores
    -- archivados (consulta histórica para Inspección o auditoría) pero
    -- solo uno activo por año.
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_calendars PRIMARY KEY (id),
    CONSTRAINT fk_work_calendars_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_work_calendars_company_year (company_id, year),
    -- Un solo calendario activo por (empresa, año). Si se cambia,
    -- el anterior se marca active=false manualmente (no se borra para
    -- preservar histórico).
    UNIQUE KEY uk_work_calendars_company_year_active (company_id, year, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE holidays (
    id CHAR(36) NOT NULL,
    work_calendar_id CHAR(36) NOT NULL,
    holiday_date DATE NOT NULL,
    name VARCHAR(200) NOT NULL,
    -- Ámbito del festivo. Importante para reporting y para que la UI
    -- explique al empresario por qué cada día es festivo.
    --   NATIONAL: BOE — Resolución Dirección General de Trabajo.
    --   CCAA:     Boletín autonómico equivalente.
    --   LOCAL:    Pleno municipal o convenio.
    scope VARCHAR(20) NOT NULL,
    is_paid BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_holidays PRIMARY KEY (id),
    CONSTRAINT fk_holidays_calendar FOREIGN KEY (work_calendar_id)
        REFERENCES work_calendars (id) ON DELETE CASCADE,
    CONSTRAINT ck_holidays_scope CHECK (scope IN ('NATIONAL', 'CCAA', 'LOCAL')),
    -- No puede haber dos festivos en la misma fecha dentro del mismo
    -- calendario. Si el BOE y el boletín autonómico coinciden en la
    -- misma fecha, prevalece el NATIONAL.
    UNIQUE KEY uk_holidays_calendar_date (work_calendar_id, holiday_date),
    INDEX ix_holidays_date (holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
