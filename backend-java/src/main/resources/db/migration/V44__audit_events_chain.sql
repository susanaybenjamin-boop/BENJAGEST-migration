-- ===========================================================================
-- V44__audit_events_chain.sql
--
-- AUDIT-CHAIN (2026-06-05): hash encadenado para audit_events.
--
-- El registro de auditoría general no estaba cubierto por el RD 1007/2023
-- estricto (eso es para el SIF), pero el ESPÍRITU de la normativa y las
-- auditorías AEAT/Hacienda piden inalterabilidad demostrable de cualquier
-- log que se presente como prueba. Hasta hoy solo teníamos SHA-256 del
-- documento exportado — eso protege contra manipulación POST-export pero
-- no contra alguien con acceso a BD que modifique una fila antes del
-- export.
--
-- Esquema:
--   - sequence_number BIGINT — posición en la cadena de esa empresa
--     (no global, ver decisión Benjamin 2026-06-05). Empieza en 1.
--   - prev_event_hash CHAR(64) — hash del evento anterior de la misma
--     empresa. NULL en el primer evento de cada cadena.
--   - event_hash CHAR(64) — SHA-256 del payload + prev_hash. Permite
--     recalcular y detectar manipulación.
--
-- Backfill: itera por empresa y recalcula los hashes en orden cronológico
-- (created_at, id) usando un cursor de PL/SQL. Idempotente: si event_hash
-- ya está rellenado en TODAS las filas de la empresa, no rehace.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) Añadir columnas (idempotente).
-- ---------------------------------------------------------------------------
SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_events'
               AND COLUMN_NAME = 'sequence_number');
SET @s := IF(@c1 = 0, 'ALTER TABLE audit_events ADD COLUMN sequence_number BIGINT NULL AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_events'
               AND COLUMN_NAME = 'prev_event_hash');
SET @s := IF(@c2 = 0, 'ALTER TABLE audit_events ADD COLUMN prev_event_hash CHAR(64) NULL AFTER sequence_number', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c3 := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_events'
               AND COLUMN_NAME = 'event_hash');
SET @s := IF(@c3 = 0, 'ALTER TABLE audit_events ADD COLUMN event_hash CHAR(64) NULL AFTER prev_event_hash', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2) Índice por (company_id, sequence_number) — usado por la búsqueda del
--    último hash en cada insert y por la verificación de la cadena.
-- ---------------------------------------------------------------------------
SET @ix := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_events'
               AND INDEX_NAME = 'ix_audit_events_chain');
SET @s := IF(@ix = 0,
    'ALTER TABLE audit_events ADD INDEX ix_audit_events_chain (company_id, sequence_number)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3) Backfill cronológico por empresa.
--    El procedure recorre cada empresa con eventos huérfanos
--    (sequence_number IS NULL) y les rellena la cadena hasta el último.
--    Algoritmo de hash: SHA2(prev_hash + sequence + payload, 256).
--    El payload concatena los campos canónicos separados por '|'.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS p_audit_chain_backfill;

DELIMITER //
CREATE PROCEDURE p_audit_chain_backfill()
BEGIN
    DECLARE done_companies INT DEFAULT FALSE;
    DECLARE v_company VARCHAR(36);
    DECLARE cur_companies CURSOR FOR
        SELECT DISTINCT company_id FROM audit_events
         WHERE sequence_number IS NULL AND company_id IS NOT NULL;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done_companies = TRUE;

    OPEN cur_companies;
    company_loop: LOOP
        FETCH cur_companies INTO v_company;
        IF done_companies THEN
            LEAVE company_loop;
        END IF;

        -- Punto de partida: secuencia siguiente al máximo ya asignado
        -- de esta empresa (0 si nunca había nada).
        SET @seq := IFNULL((SELECT MAX(sequence_number) FROM audit_events
                            WHERE company_id = v_company), 0);
        SET @prev := IFNULL((SELECT event_hash FROM audit_events
                            WHERE company_id = v_company
                              AND sequence_number = @seq
                            LIMIT 1), '');

        -- Itera por los eventos pendientes en orden cronológico.
        block: BEGIN
            DECLARE done_events INT DEFAULT FALSE;
            DECLARE v_id, v_user, v_etype, v_ettype, v_eid, v_res, v_det VARCHAR(500);
            DECLARE v_created TIMESTAMP;
            DECLARE cur_events CURSOR FOR
                SELECT id, IFNULL(user_id, ''), IFNULL(event_type, ''),
                       IFNULL(entity_type, ''), IFNULL(entity_id, ''),
                       IFNULL(result, ''), IFNULL(details, ''), created_at
                  FROM audit_events
                 WHERE company_id = v_company
                   AND sequence_number IS NULL
                 ORDER BY created_at ASC, id ASC;
            DECLARE CONTINUE HANDLER FOR NOT FOUND SET done_events = TRUE;
            OPEN cur_events;
            event_loop: LOOP
                FETCH cur_events INTO v_id, v_user, v_etype, v_ettype, v_eid, v_res, v_det, v_created;
                IF done_events THEN LEAVE event_loop; END IF;
                SET @seq := @seq + 1;
                SET @payload := CONCAT_WS('|', @prev, @seq, v_company, v_user,
                                            v_etype, v_ettype, v_eid, v_res, v_det,
                                            DATE_FORMAT(v_created, '%Y-%m-%dT%H:%i:%s'));
                SET @hash := SHA2(@payload, 256);
                UPDATE audit_events
                   SET sequence_number = @seq,
                       prev_event_hash = NULLIF(@prev, ''),
                       event_hash = @hash
                 WHERE id = v_id;
                SET @prev := @hash;
            END LOOP;
            CLOSE cur_events;
        END block;
    END LOOP;
    CLOSE cur_companies;
END //
DELIMITER ;

CALL p_audit_chain_backfill();
DROP PROCEDURE p_audit_chain_backfill;
