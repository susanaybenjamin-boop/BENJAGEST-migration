-- ===========================================================================
-- V122 — Corrige las bases de cotización por grupo 2026 con las cifras
-- OFICIALES de la Orden PJC/297/2026, de 30 de marzo (BOE-A-2026-7296), que
-- desarrolla las normas de cotización para 2026 (efectos desde 01-01-2026).
--
-- El seed de V121 traía las mínimas de 2025 como placeholder. Aquí las
-- sustituimos por las oficiales 2026 y quitamos la marca pending_validation.
--   * Tope máximo común 2026: 5.101,20 €/mes  (170,04 €/día para grupos 8-11).
--   * Bases mínimas mensuales (grupos 1-7) y diarias (grupos 8-11) por grupo.
-- Las mínimas se actualizaron al alza del SMI 2026 (RD 126/2026) + 1/6
-- (art. 19.2 LGSS). Verificar contra el BOE si hay rectificaciones posteriores.
-- ===========================================================================

UPDATE ss_contribution_group_bases SET base_min = 1989.30, base_max = 5101.20, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 1;
UPDATE ss_contribution_group_bases SET base_min = 1649.70, base_max = 5101.20, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 2;
UPDATE ss_contribution_group_bases SET base_min = 1435.20, base_max = 5101.20, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 3;
UPDATE ss_contribution_group_bases SET base_min = 1424.40, base_max = 5101.20, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 4;
UPDATE ss_contribution_group_bases SET base_min = 1424.40, base_max = 5101.20, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 5;
UPDATE ss_contribution_group_bases SET base_min = 1424.40, base_max = 5101.20, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 6;
UPDATE ss_contribution_group_bases SET base_min = 1424.40, base_max = 5101.20, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 7;
UPDATE ss_contribution_group_bases SET base_min = 47.48, base_max = 170.04, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 8;
UPDATE ss_contribution_group_bases SET base_min = 47.48, base_max = 170.04, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 9;
UPDATE ss_contribution_group_bases SET base_min = 47.48, base_max = 170.04, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 10;
UPDATE ss_contribution_group_bases SET base_min = 47.48, base_max = 170.04, pending_validation = FALSE WHERE year_number = 2026 AND cotiz_group = 11;
