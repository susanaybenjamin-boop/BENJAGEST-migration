-- =============================================================================
-- V74 — Catálogos para el bloque CTR (Contratos).
--
-- Tres tablas catálogo + seed legal vigente 2026:
--
--   1) sepe_contract_types         — códigos SEPE oficiales para Contrat@.
--                                    Post reforma laboral 2022 (RD-Ley 32/2021)
--                                    con cambios SEPE de 30-mar-2022:
--                                    eliminado obra y servicio; solo temporal
--                                    por producción/sustitución; mejora
--                                    ocupabilidad (405/505); fondos europeos
--                                    (406/506); formación en alternancia y
--                                    práctica profesional modernizadas.
--
--   2) collective_agreements +     — los 25 convenios colectivos más usados
--      professional_categories       en PYMEs españolas (estatales) con sus
--                                    grupos profesionales y salario mínimo
--                                    anual 2026 aproximado (el OWNER puede
--                                    editar después si su convenio sectorial/
--                                    provincial difiere).
--
--   3) contract_clause_templates    — anexos / cláusulas adicionales (anexos)
--                                    que se concatenan al PDF principal en
--                                    CTR-7. Seed con los más usados;
--                                    company_id NULL = built-in (no editable);
--                                    OWNER puede añadir custom por asesoría.
--
-- =============================================================================

-- ===========================================================================
-- 1) sepe_contract_types — catálogo SEPE oficial (Contrat@)
-- ===========================================================================
CREATE TABLE sepe_contract_types (
    code VARCHAR(4) NOT NULL,
    family VARCHAR(30) NOT NULL,
    working_day VARCHAR(30) NOT NULL,
    description VARCHAR(200) NOT NULL,
    legal_basis VARCHAR(200) NULL,
    -- Modelo unificado 2022 (TRUE) vs cláusulado clásico por código (FALSE).
    -- Reforma RD-Ley 32/2021 + nuevos modelos SEPE 30-mar-2022 introducen el
    -- "modelo único" que el wizard CTR-2 prefiere; los códigos pre-2022
    -- siguen vigentes y son válidos.
    unified_model_2022 BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_sepe_contract_types PRIMARY KEY (code),
    CONSTRAINT ck_sepe_family CHECK (family IN (
        'INDEFINIDO', 'TEMPORAL', 'FORMATIVO', 'PRACTICAS',
        'INSERCION', 'FONDOS_EUROPEOS', 'DISCAPACIDAD', 'OTHER'
    )),
    CONSTRAINT ck_sepe_working_day CHECK (working_day IN (
        'FULL_TIME', 'PART_TIME', 'FIXED_DISCONTINUOUS', 'OTHER'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed catálogo SEPE — códigos vigentes 2026 ===============================
INSERT INTO sepe_contract_types (code, family, working_day, description, legal_basis, unified_model_2022) VALUES
-- INDEFINIDOS ordinarios
('100', 'INDEFINIDO', 'FULL_TIME',  'Indefinido ordinario a jornada completa',                          'ET art. 15 (presunción indefinido tras Reforma 2022)', TRUE),
('109', 'INDEFINIDO', 'FULL_TIME',  'Indefinido ordinario a jornada completa con bonificación / reducción cuotas SS', 'Normativa de bonificaciones a la contratación', TRUE),
('150', 'INDEFINIDO', 'PART_TIME',  'Indefinido a tiempo parcial ordinario',                            'ET art. 12', TRUE),
('156', 'INDEFINIDO', 'PART_TIME',  'Indefinido a tiempo parcial con bonificación / reducción cuotas', 'Normativa de bonificaciones',   TRUE),
('189', 'INDEFINIDO', 'FULL_TIME',  'Conversión a indefinido bonificada (desde temporal/formativo)',    'Disposiciones de fomento del empleo estable', TRUE),
('200', 'INDEFINIDO', 'FIXED_DISCONTINUOUS', 'Indefinido fijo-discontinuo',                              'ET art. 16 (Reforma 2022)', TRUE),
('230', 'INDEFINIDO', 'FIXED_DISCONTINUOUS', 'Indefinido fijo-discontinuo con bonificación',             'Normativa de bonificaciones', TRUE),
-- INDEFINIDOS personas con discapacidad
('130', 'DISCAPACIDAD', 'FULL_TIME', 'Indefinido a jornada completa para personas con discapacidad',     'Ley 13/1982 y disposiciones de fomento', TRUE),
('137', 'DISCAPACIDAD', 'PART_TIME', 'Indefinido a tiempo parcial para personas con discapacidad',       'Ley 13/1982 y disposiciones de fomento', TRUE),
('138', 'DISCAPACIDAD', 'FULL_TIME', 'Indefinido a jornada completa para personas con discapacidad procedentes de Centros Especiales de Empleo', 'Ley 13/1982', TRUE),
('139', 'DISCAPACIDAD', 'PART_TIME', 'Indefinido a tiempo parcial para personas con discapacidad procedentes de CEE', 'Ley 13/1982', TRUE),
-- TEMPORALES post-Reforma 2022: SOLO producción y sustitución
('410', 'TEMPORAL', 'FULL_TIME', 'Temporal por circunstancias de la producción a jornada completa',     'ET art. 15.2 (Reforma 2022)', TRUE),
('510', 'TEMPORAL', 'PART_TIME', 'Temporal por circunstancias de la producción a tiempo parcial',       'ET art. 15.2 (Reforma 2022)', TRUE),
('411', 'TEMPORAL', 'FULL_TIME', 'Temporal por sustitución de persona trabajadora a jornada completa',  'ET art. 15.3 (Reforma 2022)', TRUE),
('511', 'TEMPORAL', 'PART_TIME', 'Temporal por sustitución de persona trabajadora a tiempo parcial',    'ET art. 15.3 (Reforma 2022)', TRUE),
-- FORMACIÓN EN ALTERNANCIA (sustituye al antiguo "aprendizaje")
('421', 'FORMATIVO', 'FULL_TIME', 'Formación en alternancia a jornada completa',                        'ET art. 11.2 (Reforma 2022)', TRUE),
('521', 'FORMATIVO', 'PART_TIME', 'Formación en alternancia a tiempo parcial',                          'ET art. 11.2 (Reforma 2022)', TRUE),
-- PRÁCTICA PROFESIONAL (sustituye a "prácticas" tradicional)
('401', 'PRACTICAS', 'FULL_TIME', 'Práctica profesional a jornada completa',                            'ET art. 11.3 (Reforma 2022)', TRUE),
('501', 'PRACTICAS', 'PART_TIME', 'Práctica profesional a tiempo parcial',                              'ET art. 11.3 (Reforma 2022)', TRUE),
-- MEJORA DE LA OCUPABILIDAD (nuevo Reforma 2022)
('405', 'INSERCION', 'FULL_TIME', 'Mejora de la ocupabilidad y empleabilidad a jornada completa (máx 12 meses)', 'Reforma 2022, fomento empleabilidad', TRUE),
('505', 'INSERCION', 'PART_TIME', 'Mejora de la ocupabilidad y empleabilidad a tiempo parcial (máx 12 meses)',   'Reforma 2022, fomento empleabilidad', TRUE),
-- FONDOS EUROPEOS (nuevo Reforma 2022)
('406', 'FONDOS_EUROPEOS', 'FULL_TIME', 'Temporal vinculado a programas financiados con Fondos Europeos (jornada completa)', 'Reforma 2022 + planes UE', TRUE),
('506', 'FONDOS_EUROPEOS', 'PART_TIME', 'Temporal vinculado a programas financiados con Fondos Europeos (tiempo parcial)',   'Reforma 2022 + planes UE', TRUE),
-- Otros vigentes (parciales y especiales)
('250', 'INDEFINIDO', 'FULL_TIME', 'Indefinido para mayores de 52 años bonificado',                     'Disposiciones de fomento del empleo de mayores', TRUE),
('351', 'INDEFINIDO', 'FULL_TIME', 'Indefinido para víctimas de violencia de género / trata',           'Ley Orgánica 1/2004 y normativa concordante', TRUE),
('352', 'INDEFINIDO', 'PART_TIME', 'Indefinido a tiempo parcial para víctimas de violencia de género', 'Ley Orgánica 1/2004',           TRUE),
-- Códigos legacy aún válidos para contratos pre-reforma vigentes
-- (solo para contratos firmados antes de marzo 2022 que sigan en vigor;
-- los nuevos contratos NO deben usar estos códigos)
('300', 'TEMPORAL', 'FULL_TIME', 'Temporal por obra o servicio (LEGACY pre-Reforma 2022 — DEROGADO para nuevos contratos)', 'ET pre-Reforma 2022 — DEROGADO', FALSE),
('420', 'TEMPORAL', 'FULL_TIME', 'Temporal eventual por circunstancias de producción (LEGACY pre-Reforma 2022)', 'ET pre-Reforma 2022 — DEROGADO', FALSE)
ON DUPLICATE KEY UPDATE description = VALUES(description), updated_at = NOW();

-- ===========================================================================
-- 2) collective_agreements + professional_categories
-- ===========================================================================
CREATE TABLE collective_agreements (
    id CHAR(36) NOT NULL,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(250) NOT NULL,
    scope VARCHAR(20) NOT NULL DEFAULT 'STATE',
    boe_reference VARCHAR(200) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_collective_agreements PRIMARY KEY (id),
    CONSTRAINT uk_collective_agreements_code UNIQUE (code),
    CONSTRAINT ck_collective_agreements_scope CHECK (scope IN (
        'STATE', 'AUTONOMOUS', 'PROVINCIAL', 'LOCAL', 'COMPANY'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE professional_categories (
    id CHAR(36) NOT NULL,
    collective_agreement_id CHAR(36) NOT NULL,
    -- Grupo profesional dentro del convenio. Estatuto art. 22 — clasificación
    -- por aptitudes profesionales y titulación; cada convenio define los
    -- suyos. Los más comunes: Grupo 1 (Técnicos), 2 (Jefes), 3 (Oficiales),
    -- 4 (Auxiliares), 5 (Subalternos).
    group_code VARCHAR(40) NOT NULL,
    category_name VARCHAR(200) NOT NULL,
    -- Salarios mínimos según tablas BOE 2026 — el OWNER puede editar.
    min_annual_salary DECIMAL(10,2) NULL,
    min_monthly_salary DECIMAL(10,2) NULL,
    max_weekly_hours DECIMAL(5,2) NOT NULL DEFAULT 40.00,
    -- Periodo de prueba ESTÁNDAR (ET art. 14); el convenio puede modificar.
    probation_days INT NOT NULL DEFAULT 60,
    year_published INT NOT NULL DEFAULT 2026,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_professional_categories PRIMARY KEY (id),
    CONSTRAINT fk_prof_cat_agreement FOREIGN KEY (collective_agreement_id)
        REFERENCES collective_agreements (id) ON DELETE CASCADE,
    INDEX ix_prof_cat_agreement (collective_agreement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed 25 convenios principales =============================================
-- Notas:
--   - Salarios son mínimos anuales 2026 aproximados; el OWNER debe
--     verificar y editar contra la tabla salarial vigente de SU convenio
--     concreto. El BOE publica revisiones a lo largo del año.
--   - Grupos profesionales simplificados (1-5) para v1; algunos convenios
--     tienen estructuras más complejas (e.g. Metal con I-XII).
--   - Periodo de prueba según convenio o ET art. 14 por defecto.

-- 01) Comercio General (estatal)
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0001-0000-0000-000000000001', 'COMERCIO_GENERAL', 'Comercio General (sectorial estatal)', 'STATE', 'BOE de actualizaciones anuales');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0001-0000-0000-000000000001', 'GRUPO_1', 'Titulado/a',                             24500.00, 1750.00, 40.0, 90, 2026),
(UUID(), 'cccccccc-0001-0000-0000-000000000001', 'GRUPO_2', 'Jefe/a de sección',                      22000.00, 1571.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0001-0000-0000-000000000001', 'GRUPO_3', 'Dependiente/a (oficial 1ª)',             18500.00, 1321.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0001-0000-0000-000000000001', 'GRUPO_4', 'Auxiliar de comercio',                   17000.00, 1214.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0001-0000-0000-000000000001', 'GRUPO_5', 'Personal de limpieza / subalterno',      15876.00, 1134.00, 40.0, 15, 2026);

-- 02) Hostelería (estatal)
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0002-0000-0000-000000000002', 'HOSTELERIA', 'Hostelería (sectorial estatal)', 'STATE', 'BOE Acuerdo Laboral Estatal Hostelería');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0002-0000-0000-000000000002', 'GRUPO_1', 'Jefe/a (cocina, sala, recepción)',       23800.00, 1700.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0002-0000-0000-000000000002', 'GRUPO_2', 'Cocinero/a profesional',                 20300.00, 1450.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0002-0000-0000-000000000002', 'GRUPO_3', 'Camarero/a',                             17500.00, 1250.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0002-0000-0000-000000000002', 'GRUPO_4', 'Ayudante / pinche',                      15876.00, 1134.00, 40.0, 15, 2026);

-- 03) Construcción (estatal)
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0003-0000-0000-000000000003', 'CONSTRUCCION', 'Construcción (Convenio General estatal)', 'STATE', 'BOE Convenio General del Sector de la Construcción');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0003-0000-0000-000000000003', 'NIVEL_VI',  'Encargado de obra',                    27500.00, 1964.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0003-0000-0000-000000000003', 'NIVEL_VIII','Oficial de 1ª',                        24800.00, 1771.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0003-0000-0000-000000000003', 'NIVEL_IX',  'Oficial de 2ª',                        22500.00, 1607.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0003-0000-0000-000000000003', 'NIVEL_X',   'Ayudante',                             20800.00, 1486.00, 40.0, 15, 2026),
(UUID(), 'cccccccc-0003-0000-0000-000000000003', 'NIVEL_XI',  'Peón especialista',                    19500.00, 1393.00, 40.0, 15, 2026),
(UUID(), 'cccccccc-0003-0000-0000-000000000003', 'NIVEL_XII', 'Peón ordinario',                       18500.00, 1321.00, 40.0, 15, 2026);

-- 04) Oficinas y Despachos
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0004-0000-0000-000000000004', 'OFICINAS_DESPACHOS', 'Oficinas y Despachos (sectorial estatal)', 'STATE', 'BOE convenios provinciales agrupados');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0004-0000-0000-000000000004', 'GRUPO_1', 'Titulado/a superior',                    28000.00, 2000.00, 40.0, 180, 2026),
(UUID(), 'cccccccc-0004-0000-0000-000000000004', 'GRUPO_2', 'Titulado/a medio',                       24500.00, 1750.00, 40.0, 90,  2026),
(UUID(), 'cccccccc-0004-0000-0000-000000000004', 'GRUPO_3', 'Administrativo/a',                       19800.00, 1414.00, 40.0, 60,  2026),
(UUID(), 'cccccccc-0004-0000-0000-000000000004', 'GRUPO_4', 'Auxiliar administrativo/a',              17200.00, 1228.00, 40.0, 30,  2026);

-- 05) Limpieza Edificios y Locales
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0005-0000-0000-000000000005', 'LIMPIEZA_EDIFICIOS', 'Limpieza de Edificios y Locales (sectorial)', 'STATE', 'BOE convenio sectorial limpieza');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0005-0000-0000-000000000005', 'GRUPO_1', 'Encargado/a general',                    22500.00, 1607.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0005-0000-0000-000000000005', 'GRUPO_2', 'Limpiador/a especialista',               17500.00, 1250.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0005-0000-0000-000000000005', 'GRUPO_3', 'Limpiador/a',                            15876.00, 1134.00, 40.0, 15, 2026);

-- 06) Transporte por Carretera de mercancías
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0006-0000-0000-000000000006', 'TRANSPORTE_CARRETERA', 'Transporte por Carretera de Mercancías', 'STATE', 'BOE acuerdos provinciales agrupados');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0006-0000-0000-000000000006', 'GRUPO_1', 'Conductor/a internacional',              26000.00, 1857.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0006-0000-0000-000000000006', 'GRUPO_2', 'Conductor/a nacional',                   22500.00, 1607.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0006-0000-0000-000000000006', 'GRUPO_3', 'Ayudante / mozo de almacén',             18000.00, 1286.00, 40.0, 30, 2026);

-- 07) Metal Madrid
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0007-0000-0000-000000000007', 'METAL_MADRID', 'Industria, Servicios e Instalaciones del Metal (Madrid)', 'PROVINCIAL', 'BOCM convenio del Metal de Madrid');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0007-0000-0000-000000000007', 'GRUPO_5', 'Oficial de 1ª del Metal',                25500.00, 1821.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0007-0000-0000-000000000007', 'GRUPO_6', 'Oficial de 2ª del Metal',                22800.00, 1629.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0007-0000-0000-000000000007', 'GRUPO_7', 'Especialista / Oficial 3ª',              20200.00, 1443.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0007-0000-0000-000000000007', 'GRUPO_8', 'Peón especializado',                     18500.00, 1321.00, 40.0, 15, 2026);

-- 08) Metal Bizkaia
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0008-0000-0000-000000000008', 'METAL_BIZKAIA', 'Industria Siderometalúrgica (Bizkaia)', 'PROVINCIAL', 'BOB convenio del Metal de Bizkaia');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0008-0000-0000-000000000008', 'GRUPO_5', 'Oficial de 1ª',                          27800.00, 1986.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0008-0000-0000-000000000008', 'GRUPO_6', 'Oficial de 2ª',                          24500.00, 1750.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0008-0000-0000-000000000008', 'GRUPO_7', 'Especialista',                           21500.00, 1536.00, 40.0, 30, 2026);

-- 09) Metal Cataluña
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0009-0000-0000-000000000009', 'METAL_CATALUNA', 'Industria Siderometalúrgica (Cataluña)', 'AUTONOMOUS', 'DOGC convenio del Metal de Cataluña');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0009-0000-0000-000000000009', 'GRUPO_5', 'Oficial de 1ª',                          26200.00, 1871.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0009-0000-0000-000000000009', 'GRUPO_6', 'Oficial de 2ª',                          23200.00, 1657.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0009-0000-0000-000000000009', 'GRUPO_7', 'Especialista',                           20800.00, 1486.00, 40.0, 30, 2026);

-- 10) Industria Cárnica
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0010-0000-0000-000000000010', 'INDUSTRIA_CARNICA', 'Industrias Cárnicas (sectorial estatal)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0010-0000-0000-000000000010', 'GRUPO_1', 'Encargado',                              21800.00, 1557.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0010-0000-0000-000000000010', 'GRUPO_2', 'Oficial de 1ª',                          19500.00, 1393.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0010-0000-0000-000000000010', 'GRUPO_3', 'Auxiliar',                               17000.00, 1214.00, 40.0, 15, 2026);

-- 11) Industria del Calzado
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0011-0000-0000-000000000011', 'INDUSTRIA_CALZADO', 'Industria del Calzado (sectorial estatal)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0011-0000-0000-000000000011', 'GRUPO_1', 'Oficial 1ª',                             19800.00, 1414.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0011-0000-0000-000000000011', 'GRUPO_2', 'Oficial 2ª',                             17800.00, 1271.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0011-0000-0000-000000000011', 'GRUPO_3', 'Peón',                                   15876.00, 1134.00, 40.0, 15, 2026);

-- 12) Industria Madera y Mueble
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0012-0000-0000-000000000012', 'MADERA_MUEBLE', 'Industria de la Madera y el Mueble (sectorial)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0012-0000-0000-000000000012', 'GRUPO_1', 'Oficial 1ª',                             20500.00, 1464.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0012-0000-0000-000000000012', 'GRUPO_2', 'Oficial 2ª',                             18200.00, 1300.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0012-0000-0000-000000000012', 'GRUPO_3', 'Especialista',                           16500.00, 1179.00, 40.0, 30, 2026);

-- 13) Artes Gráficas
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0013-0000-0000-000000000013', 'ARTES_GRAFICAS', 'Artes Gráficas, Manipulados y Editoriales (sectorial)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0013-0000-0000-000000000013', 'GRUPO_1', 'Técnico/a',                              22800.00, 1629.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0013-0000-0000-000000000013', 'GRUPO_2', 'Oficial',                                19200.00, 1371.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0013-0000-0000-000000000013', 'GRUPO_3', 'Auxiliar',                               16800.00, 1200.00, 40.0, 30, 2026);

-- 14) Sanidad Privada
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0014-0000-0000-000000000014', 'SANIDAD_PRIVADA', 'Sanidad Privada (sectorial)', 'STATE', 'BOE convenio sectorial sanidad privada');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0014-0000-0000-000000000014', 'GRUPO_1', 'Médico/a especialista',                  38000.00, 2714.00, 40.0, 180, 2026),
(UUID(), 'cccccccc-0014-0000-0000-000000000014', 'GRUPO_2', 'Enfermero/a / DUE',                      26500.00, 1893.00, 40.0, 60,  2026),
(UUID(), 'cccccccc-0014-0000-0000-000000000014', 'GRUPO_3', 'Técnico/a auxiliar enfermería (TCAE)',   19800.00, 1414.00, 40.0, 30,  2026),
(UUID(), 'cccccccc-0014-0000-0000-000000000014', 'GRUPO_4', 'Auxiliar administrativo/a',              17800.00, 1271.00, 40.0, 30,  2026);

-- 15) Enseñanza Privada Concertada
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0015-0000-0000-000000000015', 'ENSENANZA_CONCERTADA', 'Enseñanza Privada Concertada (sectorial)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0015-0000-0000-000000000015', 'GRUPO_1', 'Profesor/a Educación Secundaria',        29500.00, 2107.00, 25.0, 180, 2026),
(UUID(), 'cccccccc-0015-0000-0000-000000000015', 'GRUPO_2', 'Profesor/a Educación Primaria',          25800.00, 1843.00, 25.0, 90,  2026),
(UUID(), 'cccccccc-0015-0000-0000-000000000015', 'GRUPO_3', 'Personal de Administración y Servicios', 18500.00, 1321.00, 40.0, 60,  2026);

-- 16) Enseñanza Privada NO Concertada
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0016-0000-0000-000000000016', 'ENSENANZA_PRIVADA', 'Enseñanza Privada no Concertada (sectorial)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0016-0000-0000-000000000016', 'GRUPO_1', 'Profesor/a',                             22500.00, 1607.00, 25.0, 180, 2026),
(UUID(), 'cccccccc-0016-0000-0000-000000000016', 'GRUPO_2', 'Tutor/a / Monitor/a',                    18200.00, 1300.00, 35.0, 60,  2026),
(UUID(), 'cccccccc-0016-0000-0000-000000000016', 'GRUPO_3', 'Personal Auxiliar',                      16500.00, 1179.00, 40.0, 30,  2026);

-- 17) Consultoría TIC y Estudios de Mercado
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0017-0000-0000-000000000017', 'CONSULTORIA_TIC', 'Empresas de Consultoría, TI y Estudios de Mercado (XIX Convenio)', 'STATE', 'BOE-A-2026-9024');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0017-0000-0000-000000000017', 'GRUPO_1', 'Analista Senior / Project Manager',      32500.00, 2321.00, 40.0, 180, 2026),
(UUID(), 'cccccccc-0017-0000-0000-000000000017', 'GRUPO_2', 'Analista / Programador Senior',          26000.00, 1857.00, 40.0, 90,  2026),
(UUID(), 'cccccccc-0017-0000-0000-000000000017', 'GRUPO_3', 'Programador / Técnico',                  21800.00, 1557.00, 40.0, 60,  2026),
(UUID(), 'cccccccc-0017-0000-0000-000000000017', 'GRUPO_4', 'Operador / Junior',                      18500.00, 1321.00, 40.0, 60,  2026);

-- 18) Contact Center
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0018-0000-0000-000000000018', 'CONTACT_CENTER', 'Contact Center (sectorial estatal)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0018-0000-0000-000000000018', 'GRUPO_1', 'Coordinador/a / Supervisor/a',           21500.00, 1536.00, 39.0, 60, 2026),
(UUID(), 'cccccccc-0018-0000-0000-000000000018', 'GRUPO_2', 'Teleoperador/a especialista',            17800.00, 1271.00, 39.0, 30, 2026),
(UUID(), 'cccccccc-0018-0000-0000-000000000018', 'GRUPO_3', 'Teleoperador/a',                         16200.00, 1157.00, 39.0, 30, 2026);

-- 19) Estaciones de Servicio (gasolineras)
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0019-0000-0000-000000000019', 'ESTACIONES_SERVICIO', 'Estaciones de Servicio (sectorial estatal)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0019-0000-0000-000000000019', 'GRUPO_1', 'Jefe/a de estación',                     22500.00, 1607.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0019-0000-0000-000000000019', 'GRUPO_2', 'Expendedor/a',                           16800.00, 1200.00, 40.0, 30, 2026);

-- 20) Peluquerías, Estética y Centros de Belleza
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0020-0000-0000-000000000020', 'PELUQUERIAS_ESTETICA', 'Peluquerías, Institutos de Belleza y Gimnasios (sectorial)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0020-0000-0000-000000000020', 'GRUPO_1', 'Estilista / Esteticista titulado/a',     19500.00, 1393.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0020-0000-0000-000000000020', 'GRUPO_2', 'Oficial peluquería / estética',          17200.00, 1228.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0020-0000-0000-000000000020', 'GRUPO_3', 'Auxiliar / Ayudante',                    15876.00, 1134.00, 40.0, 15, 2026);

-- 21) Empresas de Seguridad
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0021-0000-0000-000000000021', 'SEGURIDAD_PRIVADA', 'Empresas de Seguridad (sectorial estatal)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0021-0000-0000-000000000021', 'GRUPO_1', 'Jefe/a de servicios',                    24800.00, 1771.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0021-0000-0000-000000000021', 'GRUPO_2', 'Vigilante de seguridad',                 19800.00, 1414.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0021-0000-0000-000000000021', 'GRUPO_3', 'Auxiliar de servicios',                  16500.00, 1179.00, 40.0, 30, 2026);

-- 22) Empresas Multiservicios
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0022-0000-0000-000000000022', 'MULTISERVICIOS', 'Empresas Multiservicios (sectorial)', 'STATE', 'BOE convenios provinciales agrupados');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0022-0000-0000-000000000022', 'GRUPO_1', 'Encargado/a / Coordinador/a',            21000.00, 1500.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0022-0000-0000-000000000022', 'GRUPO_2', 'Operario/a especializado',               16800.00, 1200.00, 40.0, 30, 2026),
(UUID(), 'cccccccc-0022-0000-0000-000000000022', 'GRUPO_3', 'Operario/a',                             15876.00, 1134.00, 40.0, 15, 2026);

-- 23) Servicios Funerarios
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0023-0000-0000-000000000023', 'SERVICIOS_FUNERARIOS', 'Servicios Funerarios y Cementerios (sectorial)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0023-0000-0000-000000000023', 'GRUPO_1', 'Maestro tanatopractor',                  23500.00, 1679.00, 40.0, 60, 2026),
(UUID(), 'cccccccc-0023-0000-0000-000000000023', 'GRUPO_2', 'Operario funerario',                     18800.00, 1343.00, 40.0, 30, 2026);

-- 24) Servicios Atención Personas con Discapacidad
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0024-0000-0000-000000000024', 'ATENCION_DISCAPACIDAD', 'Atención a Personas con Discapacidad (sectorial)', 'STATE', 'BOE convenio sectorial');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0024-0000-0000-000000000024', 'GRUPO_1', 'Psicólogo/a, Pedagogo/a, Médico/a',      26500.00, 1893.00, 38.5, 90, 2026),
(UUID(), 'cccccccc-0024-0000-0000-000000000024', 'GRUPO_2', 'Profesor/a, Logopeda, Fisioterapeuta',   22500.00, 1607.00, 38.5, 60, 2026),
(UUID(), 'cccccccc-0024-0000-0000-000000000024', 'GRUPO_3', 'Cuidador/a, Auxiliar técnico/a',         17800.00, 1271.00, 38.5, 30, 2026);

-- 25) Convenio "Libre / Sin convenio aplicable" — para casos atípicos
INSERT INTO collective_agreements (id, code, name, scope, boe_reference) VALUES
('cccccccc-0025-0000-0000-000000000025', 'CUSTOM_FREE', 'Sin convenio aplicable / convenio libre (el OWNER define)', 'COMPANY', 'No aplica');
INSERT INTO professional_categories (id, collective_agreement_id, group_code, category_name, min_annual_salary, min_monthly_salary, max_weekly_hours, probation_days, year_published) VALUES
(UUID(), 'cccccccc-0025-0000-0000-000000000025', 'CUSTOM', 'Categoría a definir por el empresario',   15876.00, 1134.00, 40.0, 60, 2026);


-- ===========================================================================
-- 3) contract_clause_templates — Anexos y cláusulas adicionales (CTR-7)
-- ===========================================================================
CREATE TABLE contract_clause_templates (
    id CHAR(36) NOT NULL,
    code VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    category VARCHAR(50) NOT NULL,
    legal_basis VARCHAR(300) NULL,
    -- TRUE = seed built-in disponible para todas. FALSE = creada por OWNER.
    is_built_in BOOLEAN NOT NULL DEFAULT TRUE,
    -- Para custom del OWNER. NULL si es built-in.
    company_id CHAR(36) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_clause_templates PRIMARY KEY (id),
    CONSTRAINT fk_contract_clause_company FOREIGN KEY (company_id)
        REFERENCES companies (id),
    CONSTRAINT ck_clause_category CHECK (category IN (
        'CONFIDENTIALITY', 'NON_COMPETE', 'EXCLUSIVITY', 'RETENTION_TRAINING',
        'GEOLOCATION_GDPR', 'TELEWORK', 'INTELLECTUAL_PROPERTY', 'OBJECTIVES_BONUS',
        'COMPANY_CAR', 'COMPANY_PHONE', 'EXPENSE_ALLOWANCE', 'WORKING_HOURS_FLEX',
        'CUSTOM', 'OTHER'
    )),
    INDEX ix_clause_company_category (company_id, category),
    INDEX ix_clause_builtin_active (is_built_in, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed cláusulas built-in =====================================================
-- Placeholders compatibles con CTR-4 PDF: {empleado}, {empresa}, {nif_empresa},
-- {salario_bruto_anual}, {fecha_inicio}, {duracion_meses}, {compensacion_no_competencia},
-- {periodo_no_competencia_meses}, {permanencia_meses}, {coste_formacion}.

INSERT INTO contract_clause_templates (id, code, title, body, category, legal_basis, is_built_in, company_id, active) VALUES

-- Confidencialidad
(UUID(), 'CONFIDENTIALITY_STANDARD', 'Acuerdo de confidencialidad', CONCAT(
'PRIMERA. — El/La trabajador/a {empleado} se obliga a guardar la más estricta confidencialidad sobre toda la información técnica, comercial, financiera, organizativa y de cualquier otra índole de {empresa} (NIF {nif_empresa}) y de sus clientes y proveedores a la que tenga acceso por razón de su trabajo.\n\n',
'SEGUNDA. — Esta obligación se mantendrá vigente durante toda la relación laboral y por un período de TRES (3) AÑOS tras su finalización por cualquier causa.\n\n',
'TERCERA. — Constituye información confidencial, sin carácter exhaustivo: bases de datos de clientes, listas de precios, procesos productivos, software propio, planos, planes estratégicos, propiedad intelectual e industrial, contraseñas y cualquier información etiquetada como confidencial.\n\n',
'CUARTA. — El incumplimiento podrá ser causa de despido disciplinario conforme al Estatuto de los Trabajadores y, en su caso, dar lugar a las acciones civiles y penales que correspondan.'
), 'CONFIDENTIALITY', 'ET art. 5.a) (deber básico de buena fe) + Ley de Secretos Empresariales (Ley 1/2019)', TRUE, NULL, TRUE),

-- No competencia post-contractual (Estatuto art. 21.2)
(UUID(), 'NON_COMPETE_POST', 'Pacto de no competencia post-contractual', CONCAT(
'PRIMERA. — El/La trabajador/a {empleado} se compromete a NO prestar servicios, por cuenta propia o ajena, a empresas que desarrollen actividades concurrentes con las de {empresa} durante un período de {periodo_no_competencia_meses} MESES tras la finalización de la relación laboral.\n\n',
'SEGUNDA. — La obligación se limita al ámbito territorial nacional español y al sector de actividad de {empresa}.\n\n',
'TERCERA. — Como compensación económica adecuada exigida por el art. 21.2 del Estatuto de los Trabajadores, {empresa} abonará al trabajador la cantidad bruta de {compensacion_no_competencia} euros, distribuida mensualmente durante el período de no competencia o, alternativamente, en pago único al finalizar la relación laboral.\n\n',
'CUARTA. — El incumplimiento del pacto dará lugar a la devolución íntegra de las cantidades percibidas como compensación, además de la indemnización de daños y perjuicios que proceda.'
), 'NON_COMPETE', 'Estatuto de los Trabajadores art. 21.2 (no competencia post-contractual). Requisitos: interés industrial/comercial efectivo + compensación económica adecuada + máx. 2 años técnicos / 6 meses resto', TRUE, NULL, TRUE),

-- Exclusividad / plena dedicación (ET art. 21.1)
(UUID(), 'EXCLUSIVITY_FULL_DEDICATION', 'Pacto de plena dedicación / exclusividad', CONCAT(
'PRIMERA. — El/La trabajador/a {empleado} se compromete a prestar sus servicios en régimen de PLENA DEDICACIÓN para {empresa}, no pudiendo prestar servicios a terceros, por cuenta propia ni ajena, durante toda la duración del presente contrato.\n\n',
'SEGUNDA. — En contraprestación, {empresa} abona al trabajador un complemento salarial específico por plena dedicación incluido en el salario bruto anual de {salario_bruto_anual} euros.\n\n',
'TERCERA. — El/La trabajador/a podrá denunciar este pacto con preaviso mínimo de TREINTA (30) DÍAS, perdiendo el complemento desde la efectividad de la denuncia, conforme al art. 21.1 ET.'
), 'EXCLUSIVITY', 'Estatuto de los Trabajadores art. 21.1 (plena dedicación)', TRUE, NULL, TRUE),

-- Permanencia tras formación (ET art. 21.4)
(UUID(), 'RETENTION_AFTER_TRAINING', 'Pacto de permanencia tras formación especializada', CONCAT(
'PRIMERA. — Habiendo realizado {empresa} una inversión en formación especializada del trabajador {empleado} por importe de {coste_formacion} euros, ambas partes acuerdan un pacto de permanencia.\n\n',
'SEGUNDA. — El/La trabajador/a se compromete a continuar prestando servicios en {empresa} durante un período mínimo de {permanencia_meses} MESES desde la finalización de la formación, plazo que en ningún caso podrá superar los DOS (2) AÑOS que marca el art. 21.4 del Estatuto de los Trabajadores.\n\n',
'TERCERA. — El incumplimiento del pacto por causa imputable al trabajador dará derecho a {empresa} a una indemnización proporcional al tiempo no cumplido del pacto, con base en el coste real de la formación reseñado en la cláusula primera.'
), 'RETENTION_TRAINING', 'Estatuto de los Trabajadores art. 21.4 (permanencia tras formación). Máximo 2 años.', TRUE, NULL, TRUE),

-- Geolocalización GDPR (para fichaje móvil)
(UUID(), 'GEOLOCATION_GDPR', 'Información y consentimiento de geolocalización (RGPD)', CONCAT(
'INFORMACIÓN BÁSICA SOBRE PROTECCIÓN DE DATOS\n\n',
'RESPONSABLE: {empresa}, NIF {nif_empresa}.\n',
'FINALIDAD: registro de jornada laboral (RD-Ley 8/2019) mediante app móvil con geolocalización en el momento de fichar entrada/salida.\n',
'LEGITIMACIÓN: cumplimiento de obligación legal (art. 6.1.c RGPD) y, en su caso, interés legítimo del responsable (art. 6.1.f RGPD).\n',
'DESTINATARIOS: solo {empresa}; no se ceden a terceros salvo obligación legal (Inspección de Trabajo).\n',
'DERECHOS: acceso, rectificación, supresión, limitación, oposición y portabilidad ante {empresa} o ante la Agencia Española de Protección de Datos (www.aepd.es).\n',
'CONSERVACIÓN: cuatro (4) años conforme al RD-Ley 8/2019 art. 34.9.\n\n',
'El/La trabajador/a {empleado} declara haber sido informado/a y CONSIENTE expresamente el tratamiento de datos de geolocalización en los términos descritos.'
), 'GEOLOCATION_GDPR', 'RGPD Reglamento (UE) 2016/679 + LOPDGDD 3/2018 + RD-Ley 8/2019 art. 34.9 (registro de jornada)', TRUE, NULL, TRUE),

-- Teletrabajo (Ley 10/2021)
(UUID(), 'TELEWORK_REGULAR', 'Acuerdo de trabajo a distancia', CONCAT(
'PRIMERA. — De conformidad con la Ley 10/2021, de 9 de julio, de trabajo a distancia, {empresa} y el/la trabajador/a {empleado} acuerdan que parte de la jornada se preste en modalidad de TELETRABAJO.\n\n',
'SEGUNDA. — DISTRIBUCIÓN. Régimen porcentual: ____ % presencial y ____ % a distancia, con la siguiente cadencia semanal a concretar entre las partes. El trabajo a distancia se realizará en el domicilio del trabajador o lugar libremente elegido por éste salvo causa justificada.\n\n',
'TERCERA. — MEDIOS Y COMPENSACIÓN DE GASTOS. {empresa} pondrá a disposición del trabajador los medios técnicos necesarios. Como compensación por los gastos derivados del trabajo a distancia (electricidad, conexión, desgaste de equipos propios), {empresa} abonará un complemento mensual conforme a la tabla anexa o, en su defecto, conforme al convenio colectivo aplicable.\n\n',
'CUARTA. — JORNADA Y DESCONEXIÓN DIGITAL. El/La trabajador/a registrará su jornada con los mismos medios que el personal presencial. Se garantiza el derecho a la desconexión digital fuera del horario laboral (art. 88 LOPDGDD).\n\n',
'QUINTA. — REVERSIBILIDAD. La modalidad de teletrabajo es reversible por cualquiera de las partes con preaviso mínimo de DIEZ (10) días.'
), 'TELEWORK', 'Ley 10/2021, de trabajo a distancia + LOPDGDD art. 88 (desconexión digital)', TRUE, NULL, TRUE),

-- Propiedad intelectual
(UUID(), 'IP_TRANSFER', 'Cesión de derechos de propiedad intelectual e industrial', CONCAT(
'PRIMERA. — El/La trabajador/a {empleado} cede a {empresa} los derechos de explotación de las obras o invenciones que cree, desarrolle o invente como consecuencia directa de su actividad laboral o utilizando medios, conocimientos o información de la empresa, conforme al Texto Refundido de la Ley de Propiedad Intelectual y a la Ley 24/2015 de Patentes.\n\n',
'SEGUNDA. — La cesión incluye los derechos de reproducción, distribución, comunicación pública y transformación, en cualquier modalidad de explotación, ámbito territorial mundial y por el plazo máximo legal.\n\n',
'TERCERA. — El/La trabajador/a se obliga a comunicar inmediatamente a {empresa} cualquier creación o invención que realice durante la vigencia del contrato y por los SEIS (6) MESES siguientes a su extinción.'
), 'INTELLECTUAL_PROPERTY', 'TRLPI (RDLeg 1/1996) art. 51 + Ley 24/2015 de Patentes arts. 15-21 (invenciones laborales)', TRUE, NULL, TRUE),

-- Objetivos / bonus
(UUID(), 'BONUS_OBJECTIVES', 'Retribución variable por objetivos', CONCAT(
'PRIMERA. — Además del salario fijo establecido, {empresa} podrá abonar al trabajador/a {empleado} una retribución variable anual en función del cumplimiento de objetivos cuantificables y verificables.\n\n',
'SEGUNDA. — Los objetivos serán comunicados por escrito al trabajador al inicio de cada ejercicio. El importe máximo de la retribución variable será de hasta el ____ % del salario bruto anual.\n\n',
'TERCERA. — El devengo y la liquidación se efectuarán anualmente, en la nómina del mes de febrero del ejercicio siguiente al evaluado. El/La trabajador/a deberá estar en alta a fecha de devengo, salvo causa de despido improcedente o extinción por causa objetiva no imputable a la conducta del trabajador.\n\n',
'CUARTA. — La retribución variable no consolida ni genera derecho adquirido para ejercicios futuros.'
), 'OBJECTIVES_BONUS', 'Estatuto de los Trabajadores art. 26 (concepto de salario) y normativa convencional', TRUE, NULL, TRUE),

-- Coche de empresa
(UUID(), 'COMPANY_CAR', 'Cesión de vehículo de empresa', CONCAT(
'PRIMERA. — {empresa} cede al trabajador/a {empleado} para uso profesional y mixto el vehículo de empresa que se identificará en anexo. El vehículo está asegurado a todo riesgo por cuenta de {empresa}.\n\n',
'SEGUNDA. — VALORACIÓN A EFECTOS DE IRPF. El uso particular del vehículo se valorará conforme a las reglas del art. 43 de la Ley 35/2006 del IRPF. {empresa} aplicará la retención y la información correspondientes.\n\n',
'TERCERA. — DEVOLUCIÓN. El vehículo, así como sus llaves y documentación, deberá ser devuelto a {empresa} en el momento de la finalización de la relación laboral o ante la pérdida de las condiciones que motivaron su cesión.\n\n',
'CUARTA. — INFRACCIONES. Las multas y sanciones derivadas del uso del vehículo serán de cargo del trabajador siempre que sean por hechos imputables al mismo durante el uso particular.'
), 'COMPANY_CAR', 'Ley 35/2006 del IRPF art. 43 (retribución en especie) + normativa de circulación', TRUE, NULL, TRUE),

-- Teléfono móvil / dispositivos
(UUID(), 'COMPANY_PHONE', 'Cesión de teléfono móvil y dispositivos', CONCAT(
'PRIMERA. — {empresa} cede al trabajador/a {empleado} un teléfono móvil y/o portátil/tablet para uso laboral. Se identifican en anexo.\n\n',
'SEGUNDA. — USO. El uso es preferentemente profesional. {empresa} autoriza un uso personal razonable. {empresa} no monitorizará comunicaciones personales realizadas con los dispositivos cedidos salvo en los términos legalmente previstos (art. 87 LOPDGDD).\n\n',
'TERCERA. — DEVOLUCIÓN. El/La trabajador/a se obliga a devolver los dispositivos en el momento de finalización de la relación laboral en correcto estado, sin perjuicio del desgaste normal por el uso.'
), 'COMPANY_PHONE', 'LOPDGDD art. 87 (derechos digitales en el ámbito laboral)', TRUE, NULL, TRUE),

-- Dietas / gastos
(UUID(), 'EXPENSE_ALLOWANCE', 'Régimen de dietas y compensación de gastos', CONCAT(
'PRIMERA. — Los desplazamientos que el/la trabajador/a {empleado} realice por orden de {empresa} fuera del centro de trabajo habitual darán derecho a la compensación de los gastos en que incurra.\n\n',
'SEGUNDA. — DIETAS Y KILOMETRAJE. Las dietas y el kilometraje se abonarán conforme a los importes exentos del art. 9 del Reglamento del IRPF (RD 439/2007), salvo que el convenio colectivo establezca importes superiores.\n\n',
'TERCERA. — JUSTIFICACIÓN. La compensación se efectuará previa presentación de facturas o documentos justificativos. Sin justificantes, se aplicarán los importes exentos del Reglamento del IRPF.'
), 'EXPENSE_ALLOWANCE', 'Reglamento del IRPF (RD 439/2007) art. 9', TRUE, NULL, TRUE),

-- Horario flexible
(UUID(), 'WORKING_HOURS_FLEX', 'Pacto de distribución irregular y flexibilidad horaria', CONCAT(
'PRIMERA. — De conformidad con el art. 34.2 del Estatuto de los Trabajadores, {empresa} y el/la trabajador/a {empleado} acuerdan una distribución irregular de la jornada anual en términos compatibles con el convenio colectivo aplicable.\n\n',
'SEGUNDA. — FLEXIBILIDAD ENTRADA Y SALIDA. El/La trabajador/a podrá distribuir su jornada diaria con una flexibilidad horaria de entrada y salida de hasta UNA HORA, manteniendo el cómputo semanal pactado.\n\n',
'TERCERA. — REGISTRO. Toda la jornada quedará registrada conforme al RD-Ley 8/2019 art. 34.9.'
), 'WORKING_HOURS_FLEX', 'Estatuto de los Trabajadores art. 34.2 + RD-Ley 8/2019 art. 34.9', TRUE, NULL, TRUE)

ON DUPLICATE KEY UPDATE updated_at = NOW();
