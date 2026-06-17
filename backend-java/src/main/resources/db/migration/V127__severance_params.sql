-- ===========================================================================
-- V127 — N3(b): Topes de INDEMNIZACIÓN por despido en tabla no-code por año.
--
-- Hasta ahora TerminationService.computeSeverance() tenía a fuego los días/año
-- y topes de cada tipo de despido + la exención de IRPF (180.000 €). Por el
-- principio Benjamin (nada legal hardcodeado; datos editables sin tocar código)
-- pasan a esta tabla GLOBAL por año (patrón no-code igual que ss_contribution_rates
-- / reta_tramos): clonar año + editar.
--
-- La fecha REFORM_2012 (2012-02-12, RD-Ley 3/2012) NO va aquí: es un hito legal
-- fijo, no un parámetro que varíe por año.
--
-- Seed año 2012 con los valores ACTUALES (los mismos que estaban a fuego):
-- preserva EXACTAMENTE el comportamiento previo (el código no tenía lógica por
-- año, solo constantes) y deja todo editable. La búsqueda usa el último año <=
-- al del cese.
--   * Despido improcedente: 33 días/año, tope 720; tramo pre-2012: 45 días/año,
--     tope 1.260 (ET art. 56 redacc. RD-Ley 3/2012, DT 5ª).
--   * Despido objetivo: 20 días/año, tope 360 (ET art. 53.1.b).
--   * Fin de contrato temporal: 12 días/año (ET art. 49.1.c, Ley 35/2010).
--   * Exención IRPF de la indemnización: 180.000 € (LIRPF art. 7.e).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS severance_params (
    id CHAR(36) NOT NULL,
    year_number INT NOT NULL,
    unfair_days_per_year DECIMAL(6,2) NOT NULL,          -- improcedente, días/año
    unfair_cap_days INT NOT NULL,                         -- tope improcedente (mens. × 30)
    unfair_pre2012_days_per_year DECIMAL(6,2) NOT NULL,   -- tramo servicios anteriores 2012-02-12
    unfair_pre2012_cap_days INT NOT NULL,                 -- tope del tramo pre-2012
    objective_days_per_year DECIMAL(6,2) NOT NULL,        -- objetivo, días/año
    objective_cap_days INT NOT NULL,                      -- tope objetivo
    end_contract_days_per_year DECIMAL(6,2) NOT NULL,     -- fin de contrato temporal, días/año
    irpf_exempt_cap DECIMAL(12,2) NOT NULL,               -- exención IRPF de la indemnización
    legal_reference VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_severance_params PRIMARY KEY (id),
    CONSTRAINT uk_severance_params_year UNIQUE (year_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO severance_params
    (id, year_number, unfair_days_per_year, unfair_cap_days,
     unfair_pre2012_days_per_year, unfair_pre2012_cap_days,
     objective_days_per_year, objective_cap_days,
     end_contract_days_per_year, irpf_exempt_cap, legal_reference)
VALUES
    (UUID(), 2012, 33.00, 720, 45.00, 1260, 20.00, 360, 12.00, 180000.00,
     'ET art. 56 (RD-Ley 3/2012, DT 5ª) · art. 53.1.b · art. 49.1.c (Ley 35/2010) · LIRPF art. 7.e');
