-- ===========================================================================
-- V128 — N3(b): fila 2026 de topes de indemnización (severance_params).
--
-- V127 sembró 2012 (base histórica, behavior-preserving). Los valores no han
-- cambiado por ley desde entonces, pero conviene tener el AÑO VIGENTE visible
-- en la tabla (el motor busca el último año <= al del cese; con 2026 presente,
-- las nóminas/ceses de 2026 muestran y usan explícitamente el año actual).
-- 2012 se conserva para ceses con fecha anterior.
--
-- INSERT IGNORE para ser idempotente (no duplica si ya existiera la fila 2026).
-- ===========================================================================

INSERT IGNORE INTO severance_params
    (id, year_number, unfair_days_per_year, unfair_cap_days,
     unfair_pre2012_days_per_year, unfair_pre2012_cap_days,
     objective_days_per_year, objective_cap_days,
     end_contract_days_per_year, irpf_exempt_cap, legal_reference)
VALUES
    (UUID(), 2026, 33.00, 720, 45.00, 1260, 20.00, 360, 12.00, 180000.00,
     'ET art. 56 (RD-Ley 3/2012, DT 5ª) · art. 53.1.b · art. 49.1.c (Ley 35/2010) · LIRPF art. 7.e');
