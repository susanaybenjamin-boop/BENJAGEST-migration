-- ===========================================================================
-- V111 — Límite excluyente de la obligación de retener (art. 81 RIRPF) y
-- límite de la cuota (art. 85.3) — bloque IRPF-LIMITE.
--
-- art. 81: no se practica retención si la retribución anual no supera la
-- cuantía de la tabla (según situación familiar 1/2/3 y nº de hijos).
-- art. 85.3: para retribuciones <= 35.200 €, la cuota de retención no puede
-- superar el 43% de (retribución − mínimo excluido).
--
-- Valores RD 142/2024 (vigentes 2024-2026). POR AÑO y editables.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS irpf_exempt_thresholds (
    year INT NOT NULL,
    situation INT NOT NULL,      -- 1, 2, 3
    children INT NOT NULL,       -- 0, 1, 2 (2 = "2 o más")
    threshold DECIMAL(14,2) NOT NULL,
    CONSTRAINT pk_irpf_exempt_thresholds PRIMARY KEY (year, situation, children)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO irpf_exempt_thresholds (year, situation, children, threshold) VALUES
    (2026, 1, 1, 17644.00), (2026, 1, 2, 18694.00),
    (2026, 2, 0, 17197.00), (2026, 2, 1, 18130.00), (2026, 2, 2, 19262.00),
    (2026, 3, 0, 15876.00), (2026, 3, 1, 16342.00), (2026, 3, 2, 16867.00);

ALTER TABLE irpf_retention_params
    ADD COLUMN IF NOT EXISTS limit_rate DECIMAL(6,3) NOT NULL DEFAULT 43.000 AFTER work_reduction_factor,
    ADD COLUMN IF NOT EXISTS limit_income_cap DECIMAL(14,2) NOT NULL DEFAULT 35200.00 AFTER limit_rate;
