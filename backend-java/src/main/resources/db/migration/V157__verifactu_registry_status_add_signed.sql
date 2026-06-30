-- V157 — Permitir el estado 'SIGNED' en verifactu_registry.status
--
-- BUG (desajuste schema↔código): el CHECK original de V14 solo admitía
-- ('PENDING','SENT','ACKNOWLEDGED','ERROR'), pero el slice VF-SIGN escribe
-- status='SIGNED' al firmar localmente el registro
-- (VerifactuRegistryRepository.setSignature, llamado desde
-- VerifactuRegistryService.registerIfActive durante la validación de una
-- factura). Cuando la empresa tiene certificado configurado, la firma se
-- ejecuta y el UPDATE viola ck_verifactu_registry_status → la transacción
-- de validación entera revienta con DataIntegrityViolationException y la
-- factura NO se puede validar (el frontend lo mostraba como el genérico
-- "Revisa que la factura tenga serie y al menos una línea").
--
-- El ciclo real de estados que escribe el código es:
--   PENDING (insert) → SIGNED (firma local VF-SIGN) → SENT → ACKNOWLEDGED
--   (o ERROR en cualquier punto del envío AEAT).
-- 'SIGNED' faltaba en el CHECK desde V14.
--
-- Esta migración SOLO amplía los valores permitidos del CHECK para que la
-- transición de firmado (legal, dirigida por el código) sea válida. NO
-- modifica, borra ni resetea ninguna fila del registro VeriFactu — respeta
-- la inalterabilidad del SIF (RD 1007/2023).
--
-- MariaDB exige DROP + ADD para "modificar" un CHECK (patrón ya usado en V17).

ALTER TABLE verifactu_registry
    DROP CONSTRAINT ck_verifactu_registry_status;

ALTER TABLE verifactu_registry
    ADD CONSTRAINT ck_verifactu_registry_status
        CHECK (status IN ('PENDING', 'SIGNED', 'SENT', 'ACKNOWLEDGED', 'ERROR'));
