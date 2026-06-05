-- ===========================================================================
-- V38__certificates_uploaded_by.sql
--
-- CERT-IMPORT (2026-06-04): trazabilidad de quién subió el certificado.
--
-- Caso de uso:
--   1) Modo empresario — el dueño de la empresa sube su .p12 directamente
--      desde Configuración → Certificado.
--   2) Modo asesoría — la asesoría tiene al cliente vinculado
--      (companies.parent_company_id = asesoría.id). Switchea tenant a
--      X-Company-Id = cliente.id y sube el .p12 EN NOMBRE del cliente.
--
-- En ambos casos el certificado pertenece al cliente (company_id =
-- cliente). Lo único que cambia es la trazabilidad de quién lo subió:
--
--   - uploaded_by_user_id    → id del usuario que hizo la subida.
--   - uploaded_by_company_id → empresa del usuario (puede ser distinta
--                              del tenant: NULL o = company_id si la
--                              subió alguien de la propia empresa, =
--                              asesoría.id si la subió la asesoría).
--
-- Esto permite al UI mostrar badges del tipo "Subido por tu asesoría
-- {nombre}" o "Subido por el cliente — solo lectura" según el caso.
-- ===========================================================================

-- Idempotente: el bloque PREPARE/EXECUTE evita el error si la columna
-- ya existe (MariaDB no soporta IF NOT EXISTS para ADD COLUMN antes de
-- 10.3 — aquí estamos en 11.4 pero mantenemos el guard por defensa).

SET @col_exists := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'digital_certificates'
       AND COLUMN_NAME = 'uploaded_by_user_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE digital_certificates
        ADD COLUMN uploaded_by_user_id CHAR(36) NULL AFTER active,
        ADD COLUMN uploaded_by_company_id CHAR(36) NULL AFTER uploaded_by_user_id,
        ADD CONSTRAINT fk_dc_uploaded_by_user
            FOREIGN KEY (uploaded_by_user_id) REFERENCES user_accounts (id),
        ADD CONSTRAINT fk_dc_uploaded_by_company
            FOREIGN KEY (uploaded_by_company_id) REFERENCES companies (id),
        ADD INDEX ix_dc_uploaded_by_company (uploaded_by_company_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
