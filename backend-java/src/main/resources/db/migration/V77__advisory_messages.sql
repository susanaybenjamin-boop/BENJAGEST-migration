-- V77 — Mensajería asesoría ↔ cliente.
--
-- Replicar la funcionalidad de `asesoria_mensajes_180` de CONTENDO:
-- canal de mensajes bidireccional entre la asesoría y cada uno de sus
-- clientes vinculados. Sirve para:
--   - Comunicar al cliente "se acerca el vencimiento del modelo 303".
--   - Recibir del cliente "te adjunto la factura de luz que pediste".
--   - Mantener un histórico auditable de la comunicación (cosas pasan
--     de palabra y luego nadie se acuerda; aquí hay timestamp y autor).
--
-- Decisiones de diseño:
--   - Una sola tabla, no dos. Cada fila tiene `direction` (A2C/C2A)
--     que indica si el origen es asesoría o cliente. Más fácil de
--     mostrar como timeline cronológico mezclado.
--   - `read_at` por destinatario para badges de "no leído" (1 fila
--     basta porque solo hay un destinatario por dirección).
--   - `attachment_path` opcional para adjuntar PDF (la factura pedida)
--     usando el mismo storage que ya tenemos para sales/purchase PDFs.
--   - Conversación lógica = (advisory_company_id, client_company_id).
--     Sin tabla separada de "thread" para no añadir complejidad —
--     el filtro por ambos ids ya da la lista de mensajes del thread.
--
-- Sin migración de datos: tabla nueva, vacía al arranque.

CREATE TABLE advisory_messages (
    id                    CHAR(36)     NOT NULL,
    advisory_company_id   CHAR(36)     NOT NULL,
    client_company_id     CHAR(36)     NOT NULL,
    -- Dirección: A2C (asesoría → cliente) o C2A (cliente → asesoría).
    -- El campo from_user_id ya implica la dirección, pero almacenarlo
    -- explícitamente simplifica los filtros del UI.
    direction             VARCHAR(10)  NOT NULL,
    from_user_id          CHAR(36)     NULL,  -- NULL si el sistema lo creó
    body                  TEXT         NOT NULL,
    attachment_path       VARCHAR(500) NULL,
    -- read_at: timestamp en el que el destinatario marcó el mensaje
    -- como leído. NULL = no leído. Se usa para el contador de
    -- mensajes pendientes en el sidebar.
    read_at               TIMESTAMP    NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_advisory_messages PRIMARY KEY (id),
    CONSTRAINT fk_advisory_messages_advisory FOREIGN KEY (advisory_company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_advisory_messages_client FOREIGN KEY (client_company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_advisory_messages_user FOREIGN KEY (from_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT ck_advisory_messages_direction CHECK (direction IN ('A2C', 'C2A')),

    -- Búsquedas típicas:
    --  - Lista del thread (por par): índice compuesto.
    --  - Pendientes por leer del destinatario actual: filtro por
    --    direction + read_at IS NULL en uno de los dos lados.
    INDEX ix_adv_msg_thread (advisory_company_id, client_company_id, created_at DESC),
    INDEX ix_adv_msg_unread_a2c (advisory_company_id, client_company_id, direction, read_at),
    INDEX ix_adv_msg_unread_c2a (client_company_id, advisory_company_id, direction, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
