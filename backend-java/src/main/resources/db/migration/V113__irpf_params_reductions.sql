-- ===========================================================================
-- V113 — Reducciones del algoritmo oficial de retención IRPF que faltaban
-- (bloque IRPF-LIMITE / validación contra calculadora AEAT 2026).
--
-- Verificado contra el "Algoritmo de cálculo del tipo de retención IRPF 2026"
-- (AEAT, 26-12-2025):
--   • Reducción por MÁS DE DOS DESCENDIENTES: 600 € si nº hijos > 2
--     (art. 83.3 RIRPF). Era lo que desviaba la base 600 € respecto a la AEAT.
--   • 3er tramo de la reducción por rendimientos del trabajo (art. 20):
--     17.673,52 < RNT < 19.747,50 -> 2.364,34 - 1,14 x (RNT - 17.673,52).
--
-- Todo POR AÑO y editable (filosofía Benjamin: nada a fuego).
-- ===========================================================================

ALTER TABLE irpf_retention_params
    ADD COLUMN IF NOT EXISTS work_reduction_max2 DECIMAL(14,2) NOT NULL DEFAULT 2364.34
        AFTER work_reduction_factor,
    ADD COLUMN IF NOT EXISTS work_reduction_threshold3 DECIMAL(14,2) NOT NULL DEFAULT 19747.50
        AFTER work_reduction_max2,
    ADD COLUMN IF NOT EXISTS work_reduction_factor2 DECIMAL(8,4) NOT NULL DEFAULT 1.1400
        AFTER work_reduction_threshold3,
    ADD COLUMN IF NOT EXISTS reduction_more_than_2_desc DECIMAL(14,2) NOT NULL DEFAULT 600.00
        AFTER work_reduction_factor2;
