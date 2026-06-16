-- ===========================================================================
-- V121 — Bases de cotización a la SS por GRUPO DE COTIZACIÓN y AÑO.
--
-- Hasta ahora ss_contribution_rates guardaba UN tope mínimo/máximo global por
-- año (V109). La base MÍNIMA en realidad depende del grupo de cotización
-- (1..11); la MÁXIMA es común. Esta tabla recoge la base mín/máx por grupo y
-- año para poder actualizarla desde la UI sin tocar código (patrón "no-code"
-- igual que reta_tramos / IRPF tramos): clonar año + editar.
--
-- Datos legales nacionales → tabla GLOBAL (sin company_id).
--
-- ⚠️ CIFRAS PROVISIONALES 2026 — PENDIENTES DE VALIDAR (pending_validation=TRUE):
--   * Tope MÁXIMO 2026: 5.101,20 €/mes (Orden PJC/297/2026, ya en V109) →
--     máximo diario grupos 8-11 = 5101.20/30 = 170,04 €/día.
--   * Bases MÍNIMAS por grupo: tomadas de 2025 como placeholder hasta que se
--     publiquen las de 2026. Benjamin debe validarlas/ajustarlas en la UI.
--   * Grupos 1-7: base mensual. Grupos 8-11: base DIARIA (is_daily=TRUE).
-- El motor de nóminas NO consume todavía esta tabla (sigue usando el tope
-- global de ss_contribution_rates); el cableado se hará tras validar las cifras.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS ss_contribution_group_bases (
    id CHAR(36) NOT NULL,
    year_number INT NOT NULL,
    cotiz_group SMALLINT NOT NULL,            -- 1..11
    label VARCHAR(120) NOT NULL,
    base_min DECIMAL(12,2) NOT NULL,          -- mensual (grupos 1-7) o diaria (8-11)
    base_max DECIMAL(12,2) NOT NULL,
    is_daily BOOLEAN NOT NULL DEFAULT FALSE,  -- TRUE = base diaria (grupos 8-11)
    pending_validation BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_ss_group_bases PRIMARY KEY (id),
    CONSTRAINT uk_ss_group_bases_year_group UNIQUE (year_number, cotiz_group),
    INDEX ix_ss_group_bases_year (year_number, cotiz_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed 2026 PROVISIONAL (mínimas 2025; máximas 2026). pending_validation=TRUE.
INSERT INTO ss_contribution_group_bases
    (id, year_number, cotiz_group, label, base_min, base_max, is_daily, pending_validation) VALUES
 (UUID(), 2026,  1, 'Grupo 1 — Ingenieros, Licenciados y alta dirección', 1847.40, 5101.20, FALSE, TRUE),
 (UUID(), 2026,  2, 'Grupo 2 — Ingenieros Técnicos, Peritos y Ayudantes Titulados', 1532.10, 5101.20, FALSE, TRUE),
 (UUID(), 2026,  3, 'Grupo 3 — Jefes Administrativos y de Taller', 1332.90, 5101.20, FALSE, TRUE),
 (UUID(), 2026,  4, 'Grupo 4 — Ayudantes no Titulados', 1323.00, 5101.20, FALSE, TRUE),
 (UUID(), 2026,  5, 'Grupo 5 — Oficiales Administrativos', 1323.00, 5101.20, FALSE, TRUE),
 (UUID(), 2026,  6, 'Grupo 6 — Subalternos', 1323.00, 5101.20, FALSE, TRUE),
 (UUID(), 2026,  7, 'Grupo 7 — Auxiliares Administrativos', 1323.00, 5101.20, FALSE, TRUE),
 (UUID(), 2026,  8, 'Grupo 8 — Oficiales de 1ª y 2ª (base diaria)', 44.10, 170.04, TRUE, TRUE),
 (UUID(), 2026,  9, 'Grupo 9 — Oficiales de 3ª y Especialistas (base diaria)', 44.10, 170.04, TRUE, TRUE),
 (UUID(), 2026, 10, 'Grupo 10 — Peones (base diaria)', 44.10, 170.04, TRUE, TRUE),
 (UUID(), 2026, 11, 'Grupo 11 — Trabajadores menores de 18 años (base diaria)', 44.10, 170.04, TRUE, TRUE);
