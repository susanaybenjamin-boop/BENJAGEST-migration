-- ===========================================================================
-- V135 — MEMP-5b (SIGN): evidencias de la firma electrónica simple del recibí
-- de la nómina por el empleado (estilo Sesame: acuse con sello de tiempo +
-- evidencias, sin certificado cualificado).
--
-- acknowledged_at ya existe (V116). Aquí añadimos las evidencias del acto de
-- firma: IP, dispositivo, método y un código de verificación del justificante.
-- Aditiva: no toca seeds.
-- ===========================================================================

ALTER TABLE payslips
    ADD COLUMN IF NOT EXISTS acknowledged_ip     VARCHAR(60)  NULL AFTER acknowledged_at,
    ADD COLUMN IF NOT EXISTS acknowledged_device VARCHAR(255) NULL AFTER acknowledged_ip,
    ADD COLUMN IF NOT EXISTS acknowledged_method VARCHAR(30)  NULL AFTER acknowledged_device,
    ADD COLUMN IF NOT EXISTS acknowledged_code   VARCHAR(40)  NULL AFTER acknowledged_method;
