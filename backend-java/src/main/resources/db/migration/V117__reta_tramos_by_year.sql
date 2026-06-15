-- RETA-0: tramos de cotización del autónomo por AÑO en BD (antes hardcodeados
-- en RetaService.suggestTramo). Permite actualizar la tabla cada año desde la UI
-- sin tocar código (clonar año + editar). Datos legales nacionales → globales
-- (sin company_id). Art. 308 LGSS / RD-Ley 13/2022 (cotización por rendimientos).
CREATE TABLE IF NOT EXISTS reta_tramos (
    id CHAR(36) NOT NULL,
    year_number INT NOT NULL,
    ord INT NOT NULL,
    label VARCHAR(60) NOT NULL,
    income_max_monthly DECIMAL(12,2) NOT NULL,
    base_min DECIMAL(12,2) NOT NULL,
    base_max DECIMAL(12,2) NOT NULL,
    quota_min DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_reta_tramos PRIMARY KEY (id),
    CONSTRAINT uk_reta_tramos_year_ord UNIQUE (year_number, ord),
    INDEX ix_reta_tramos_year (year_number, ord)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed 2026 (valores transitorios 2025 según Resolución 17-01-2025 BOE; ajustar
-- al publicarse el PGE 2026). Migra los valores que estaban embebidos en Java.
INSERT INTO reta_tramos (id, year_number, ord, label, income_max_monthly, base_min, base_max, quota_min) VALUES
 (UUID(), 2026,  0, 'Tramo 1 reducida',   670.00,  670.00,  718.85,  216.78),
 (UUID(), 2026,  1, 'Tramo 2 reducida',   900.00,  827.50,  899.74,  267.59),
 (UUID(), 2026,  2, 'Tramo 3 reducida',  1167.00,  952.59, 1167.00,  303.88),
 (UUID(), 2026,  3, 'Tramo 1 general',   1300.00,  960.78, 1143.79,  298.13),
 (UUID(), 2026,  4, 'Tramo 2 general',   1500.00,  960.78, 1209.39,  314.39),
 (UUID(), 2026,  5, 'Tramo 3 general',   1700.00,  960.78, 1272.87,  325.18),
 (UUID(), 2026,  6, 'Tramo 4 general',   1850.00, 1013.07, 1336.35,  335.97),
 (UUID(), 2026,  7, 'Tramo 5 general',   2030.00, 1029.41, 1454.25,  366.21),
 (UUID(), 2026,  8, 'Tramo 6 general',   2330.00, 1045.75, 1700.32,  428.99),
 (UUID(), 2026,  9, 'Tramo 7 general',   2760.00, 1078.43, 1900.10,  478.85),
 (UUID(), 2026, 10, 'Tramo 8 general',   3190.00, 1143.79, 2030.91,  511.41),
 (UUID(), 2026, 11, 'Tramo 9 general',   3620.00, 1209.15, 2346.18,  594.40),
 (UUID(), 2026, 12, 'Tramo 10 general',  4050.00, 1274.51, 2660.43,  674.40),
 (UUID(), 2026, 13, 'Tramo 11 general',  6000.00, 1356.21, 2994.95,  754.84),
 (UUID(), 2026, 14, 'Tramo 12 general',  9999.00, 1437.91, 4720.50, 1191.40);
