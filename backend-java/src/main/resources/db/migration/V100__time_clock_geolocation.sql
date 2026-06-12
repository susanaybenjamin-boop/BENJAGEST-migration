-- =============================================================================
-- V100 — GEO-FICHAR: geolocalización al fichar (RD 8/2019)
--
-- El empleado puede mandar lat/lng al fichar. TimeClockService verifica
-- contra work_centers.{lat,lng,radio_m,geo_policy} del centro asignado
-- al empleado:
--   - geo_policy=none: no verifica.
--   - geo_policy=info: registra distancia pero no avisa al UI.
--   - geo_policy=soft: warning amarillo si fuera del radio.
--   - geo_policy=strict: rechaza fichaje fuera del radio (422).
--
-- Persistimos siempre lat/lng cuando vienen (evidencia) y, si está
-- fuera, los metros como geo_warning_meters (para auditoría).
-- =============================================================================

ALTER TABLE time_clock_events
    ADD COLUMN lat DECIMAL(10,7) NULL,
    ADD COLUMN lng DECIMAL(10,7) NULL,
    ADD COLUMN geo_warning_meters INT NULL;
