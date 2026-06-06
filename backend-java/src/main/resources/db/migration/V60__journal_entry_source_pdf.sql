-- V60 — PDF original adjunto al asiento contable
--
-- Caso de uso: la asesoría importa varias facturas a la vez (gastos o
-- ventas) y se crean asientos DRAFT directos sin pasar por una factura
-- emitida/recibida completa. Para que el asesor pueda revisar antes
-- de validar, guardamos el PDF original junto al asiento y lo
-- visualizamos en la pestaña "Por validar" mientras está en DRAFT.
--
-- Cuando el asiento se valida (POSTED), el PDF sigue archivado para
-- trazabilidad fiscal pero la UI deja de mostrarlo (ya fue revisado).
--
-- Idempotente.

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'journal_entries'
       AND column_name = 'source_pdf_path'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE journal_entries ADD COLUMN source_pdf_path VARCHAR(500) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'journal_entries'
       AND column_name = 'source_pdf_sha256'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE journal_entries ADD COLUMN source_pdf_sha256 VARCHAR(64) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
