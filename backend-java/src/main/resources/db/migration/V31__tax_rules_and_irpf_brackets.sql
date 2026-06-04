-- ===========================================================================
-- V31__tax_rules_and_irpf_brackets.sql
--
-- F1 — Reglas fiscales con histórico anual.
--
-- Hasta ahora teníamos `vat_rates` por empresa (V23) que era estática:
-- si la AEAT cambia el IVA reducido del 10% al 7%, había que editar
-- la fila a mano y perdíamos histórico. Esto importa porque al re-
-- generar una factura del año pasado (rectificativas, duplicados),
-- el IVA debe ser el que estaba vigente entonces, NO el actual.
--
-- Solucion: las tablas `tax_rules_*` son globales y por año. Las
-- `vat_rates` siguen siendo personalizaciones por empresa (un cliente
-- puede tener un IVA exento custom), pero se enriquecen con valid_from
-- y valid_until.
--
--   tax_irpf_brackets — tramos IRPF estatales del año.
--   tax_irpf_regional_brackets — tramos autonómicos por CCAA.
--   tax_iae_epigraphs — catálogo de epígrafes IAE (placeholder con
--     los 20 mas comunes — completo es ~1.500).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS tax_irpf_brackets (
    year INT NOT NULL,
    bracket_order INT NOT NULL,
    min_income DECIMAL(14,2) NOT NULL,
    max_income DECIMAL(14,2) NULL,
    rate_state DECIMAL(5,2) NOT NULL,
    rate_regional DECIMAL(5,2) NOT NULL,
    notes VARCHAR(200) NULL,
    CONSTRAINT pk_tax_irpf_brackets PRIMARY KEY (year, bracket_order),
    CONSTRAINT ck_tax_irpf_brackets_rates CHECK (rate_state >= 0 AND rate_regional >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tabla 2025 (vigente al cierre 2025, presentación 2026):
-- Fuente: Ley 35/2006 art. 63 + 65 y leyes anuales PGE.
INSERT IGNORE INTO tax_irpf_brackets (year, bracket_order, min_income, max_income, rate_state, rate_regional, notes) VALUES
(2025, 1, 0.00,     12450.00, 9.50,  9.50,  'Tramo 1 - 19% conjunto'),
(2025, 2, 12450.01, 20200.00, 12.00, 12.00, 'Tramo 2 - 24% conjunto'),
(2025, 3, 20200.01, 35200.00, 15.00, 15.00, 'Tramo 3 - 30% conjunto'),
(2025, 4, 35200.01, 60000.00, 18.50, 18.50, 'Tramo 4 - 37% conjunto'),
(2025, 5, 60000.01, 300000.00, 22.50, 22.50, 'Tramo 5 - 45% conjunto'),
(2025, 6, 300000.01, NULL,    24.50, 22.50, 'Tramo 6 - 47% conjunto');

INSERT IGNORE INTO tax_irpf_brackets (year, bracket_order, min_income, max_income, rate_state, rate_regional, notes) VALUES
(2026, 1, 0.00,     12450.00, 9.50,  9.50,  'Tramo 1 - 19% conjunto'),
(2026, 2, 12450.01, 20200.00, 12.00, 12.00, 'Tramo 2 - 24% conjunto'),
(2026, 3, 20200.01, 35200.00, 15.00, 15.00, 'Tramo 3 - 30% conjunto'),
(2026, 4, 35200.01, 60000.00, 18.50, 18.50, 'Tramo 4 - 37% conjunto'),
(2026, 5, 60000.01, 300000.00, 22.50, 22.50, 'Tramo 5 - 45% conjunto'),
(2026, 6, 300000.01, NULL,    24.50, 22.50, 'Tramo 6 - 47% conjunto');

-- ---------------------------------------------------------------------------
-- IVA por año: histórico de tipos vigentes. NO sustituye a vat_rates
-- (personalizables por empresa) — es la referencia normativa.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tax_vat_history (
    year INT NOT NULL,
    vat_type VARCHAR(20) NOT NULL,
    percent DECIMAL(5,2) NOT NULL,
    description VARCHAR(200) NULL,
    legal_reference VARCHAR(200) NULL,
    CONSTRAINT pk_tax_vat_history PRIMARY KEY (year, vat_type),
    CONSTRAINT ck_tax_vat_history_type CHECK (vat_type IN ('GENERAL', 'REDUCED', 'SUPER_REDUCED', 'ZERO', 'RECARGO_EQ_GENERAL', 'RECARGO_EQ_REDUCED', 'RECARGO_EQ_SUPER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO tax_vat_history (year, vat_type, percent, description, legal_reference) VALUES
(2025, 'GENERAL', 21.00, 'IVA general', 'Ley 37/1992 art. 90'),
(2025, 'REDUCED', 10.00, 'IVA reducido', 'Ley 37/1992 art. 91.1'),
(2025, 'SUPER_REDUCED', 4.00, 'IVA superreducido', 'Ley 37/1992 art. 91.2'),
(2025, 'ZERO', 0.00, 'IVA cero (exento)', 'Ley 37/1992 art. 91.1.1.1'),
(2025, 'RECARGO_EQ_GENERAL', 5.20, 'Recargo de equivalencia general', 'Ley 37/1992 art. 161'),
(2025, 'RECARGO_EQ_REDUCED', 1.40, 'Recargo de equivalencia reducido', 'Ley 37/1992 art. 161'),
(2025, 'RECARGO_EQ_SUPER', 0.50, 'Recargo de equivalencia superreducido', 'Ley 37/1992 art. 161'),
(2026, 'GENERAL', 21.00, 'IVA general', 'Ley 37/1992 art. 90'),
(2026, 'REDUCED', 10.00, 'IVA reducido', 'Ley 37/1992 art. 91.1'),
(2026, 'SUPER_REDUCED', 4.00, 'IVA superreducido', 'Ley 37/1992 art. 91.2'),
(2026, 'ZERO', 0.00, 'IVA cero (exento)', 'Ley 37/1992 art. 91.1.1.1');

-- ---------------------------------------------------------------------------
-- Retenciones IRPF aplicables (autónomos, alquileres, intereses).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tax_withholding_history (
    year INT NOT NULL,
    withholding_type VARCHAR(40) NOT NULL,
    percent DECIMAL(5,2) NOT NULL,
    description VARCHAR(200) NULL,
    legal_reference VARCHAR(200) NULL,
    CONSTRAINT pk_tax_withholding_history PRIMARY KEY (year, withholding_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO tax_withholding_history (year, withholding_type, percent, description, legal_reference) VALUES
(2025, 'PROFESSIONAL_STANDARD', 15.00, 'Profesionales (regla general)', 'Ley 35/2006 art. 101.5'),
(2025, 'PROFESSIONAL_FIRST_YEAR', 7.00, 'Profesionales (primer ano + 2 siguientes)', 'Ley 35/2006 art. 101.5'),
(2025, 'PROFESSIONAL_REDUCED', 7.00, 'Profesionales con ingresos < 15000', 'Ley 35/2006 art. 101.5'),
(2025, 'RENTAL', 19.00, 'Alquileres inmuebles urbanos', 'Ley 35/2006 art. 100'),
(2025, 'CAPITAL_INTEREST', 19.00, 'Intereses cuentas, dividendos', 'Ley 35/2006 art. 90'),
(2025, 'CAPITAL_GAINS', 19.00, 'Ganancias patrimoniales', 'Ley 35/2006 art. 100'),
(2026, 'PROFESSIONAL_STANDARD', 15.00, 'Profesionales (regla general)', 'Ley 35/2006 art. 101.5'),
(2026, 'PROFESSIONAL_FIRST_YEAR', 7.00, 'Profesionales (primer ano + 2 siguientes)', 'Ley 35/2006 art. 101.5'),
(2026, 'PROFESSIONAL_REDUCED', 7.00, 'Profesionales con ingresos < 15000', 'Ley 35/2006 art. 101.5'),
(2026, 'RENTAL', 19.00, 'Alquileres inmuebles urbanos', 'Ley 35/2006 art. 100'),
(2026, 'CAPITAL_INTEREST', 19.00, 'Intereses cuentas, dividendos', 'Ley 35/2006 art. 90'),
(2026, 'CAPITAL_GAINS', 19.00, 'Ganancias patrimoniales', 'Ley 35/2006 art. 100');

-- ---------------------------------------------------------------------------
-- Catalogo IAE — los 20 epígrafes mas comunes para auto-completar.
-- Lista completa: ~1.500 epígrafes. Se completa en sub-slice futuro.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tax_iae_epigraphs (
    code VARCHAR(20) NOT NULL,
    section VARCHAR(20) NOT NULL,
    description VARCHAR(300) NOT NULL,
    CONSTRAINT pk_tax_iae_epigraphs PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO tax_iae_epigraphs (code, section, description) VALUES
('501.1', 'Empresarial', 'Construccion completa, reparacion y conservacion de edificaciones'),
('647.1', 'Empresarial', 'Comercio al por menor de productos alimenticios'),
('653.4', 'Empresarial', 'Comercio al por menor de materiales de construccion'),
('671.4', 'Empresarial', 'Restaurantes de un tenedor'),
('673.1', 'Empresarial', 'Cafes y bares con cocina'),
('673.2', 'Empresarial', 'Cafes y bares sin cocina'),
('691.9', 'Empresarial', 'Reparacion de articulos no especificados en otros epigrafes'),
('721.4', 'Empresarial', 'Transporte de mercancias por carretera'),
('722',   'Empresarial', 'Transporte por taxi'),
('843.9', 'Profesional', 'Otros servicios tecnicos n.c.o.p.'),
('849.5', 'Profesional', 'Diseno textil, grafico, decoracion de interiores'),
('849.6', 'Profesional', 'Servicios de colocacion y suministro de personal'),
('849.7', 'Profesional', 'Servicios de gestion administrativa'),
('934.1', 'Profesional', 'Servicios profesionales relacionados con la informatica'),
('942.1', 'Empresarial', 'Hospitales generales'),
('944.1', 'Empresarial', 'Servicios de medicina (NO incluidos en la SS)'),
('965.1', 'Empresarial', 'Espectaculos cinematograficos'),
('967.2', 'Empresarial', 'Escuelas y servicios de perfeccionamiento deportivo'),
('982.4', 'Empresarial', 'Otras actividades de ocio (parques recreativos)'),
('1972',  'Profesional', 'Periodistas');
