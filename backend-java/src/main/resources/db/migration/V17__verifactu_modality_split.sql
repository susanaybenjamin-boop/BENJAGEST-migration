-- ===========================================================================
-- V17__verifactu_modality_split.sql
--
-- Slice VF-OFF-DEPRECATE — separar dos conceptos que la V13 había
-- mezclado en una sola columna `verifactu_mode`:
--
--   - MODALIDAD (legal, RD 1007/2023): VERIFACTU o NO_VERIFACTU.
--   - ENTORNO  (técnico, solo VeriFactu): TEST o PROD del cliente AEAT.
--
-- El valor 'OFF' que teníamos NO es modalidad legal — la ley
-- (RD 1007/2023 + Orden HAC/1177/2024) reconoce solo dos modalidades.
-- Ambas exigen hash encadenado y QR en la factura; la diferencia es:
--   * VeriFactu      → envío en tiempo real a AEAT.
--   * NO VeriFactu   → no envío; AEAT lo pide si quiere + registro de
--                      eventos del sistema (Auditoría) obligatorio.
--
-- Migración:
--   - Nueva columna `verifactu_modality` (VERIFACTU | NO_VERIFACTU)
--     con default NO_VERIFACTU (la opción "más segura" para una empresa
--     que aún no ha decidido — sigue facturando con cadena hash local,
--     simplemente no envía).
--   - Las empresas que estaban en 'TEST' o 'PROD' pasan a VERIFACTU
--     (eran las que sí enviaban).
--   - Las que estaban en 'OFF' pasan a NO_VERIFACTU (sin envío, pero
--     ahora SÍ con cadena hash y QR).
--   - La columna `verifactu_mode` deja de aceptar 'OFF' y queda
--     reservada para el environment del cliente AEAT (TEST | PROD).
--     Las empresas en OFF se quedan con 'TEST' por defecto — no se usa
--     hasta que se active VERIFACTU.
--
-- Estado tras la migración:
--   modality=NO_VERIFACTU, mode=TEST  → no envía a AEAT.
--   modality=VERIFACTU,   mode=TEST   → envía a AEAT entorno pruebas.
--   modality=VERIFACTU,   mode=PROD   → envía a AEAT producción.
-- ===========================================================================

ALTER TABLE companies
    ADD COLUMN verifactu_modality VARCHAR(20) NOT NULL DEFAULT 'NO_VERIFACTU' AFTER verifactu_mode,
    ADD CONSTRAINT ck_companies_verifactu_modality CHECK (verifactu_modality IN ('VERIFACTU', 'NO_VERIFACTU'));

-- Migrar valores: TEST/PROD ya eran VeriFactu; OFF se convierte en NoVeriFactu.
UPDATE companies SET verifactu_modality = 'VERIFACTU'    WHERE verifactu_mode IN ('TEST', 'PROD');
UPDATE companies SET verifactu_modality = 'NO_VERIFACTU' WHERE verifactu_mode = 'OFF';

-- A partir de aquí, `verifactu_mode` ya solo representa el entorno del
-- cliente AEAT (TEST/PROD). Las filas que estaban en OFF pasan a TEST
-- (default seguro — no enviará nada porque la modalidad es NO_VERIFACTU).
UPDATE companies SET verifactu_mode = 'TEST' WHERE verifactu_mode = 'OFF';

-- Reemplazar el CHECK antiguo (OFF/TEST/PROD) por el nuevo (TEST/PROD).
-- MariaDB exige DROP+ADD del constraint para "modificarlo".
ALTER TABLE companies
    DROP CONSTRAINT ck_companies_verifactu_mode;
ALTER TABLE companies
    ADD CONSTRAINT ck_companies_verifactu_mode CHECK (verifactu_mode IN ('TEST', 'PROD'));
