-- =============================================================================
-- V85 — Ampliar `customers` con campos de direccion postal + codigo interno
-- + modo por defecto.
--
-- PORT-4 CLI: el editor de clientes actual solo permitia nombre fiscal +
-- NIF + contacto + email + telefono. Faltaba la informacion postal (que
-- ya estaba en CONTENDO), el codigo interno corto que la asesoria usa
-- para referirse al cliente, y el modo por defecto (operacion preferida).
--
-- Aditivo y nullable — los clientes existentes mantienen NULL hasta que
-- el usuario los edite.
-- =============================================================================

ALTER TABLE customers
    ADD COLUMN address VARCHAR(240) NULL AFTER iban,
    ADD COLUMN city VARCHAR(100) NULL AFTER address,
    ADD COLUMN province VARCHAR(100) NULL AFTER city,
    ADD COLUMN postal_code VARCHAR(20) NULL AFTER province,
    ADD COLUMN country VARCHAR(100) NULL DEFAULT 'España' AFTER postal_code,
    ADD COLUMN internal_code VARCHAR(40) NULL AFTER country,
    ADD COLUMN default_mode VARCHAR(40) NULL AFTER internal_code,
    ADD COLUMN phone VARCHAR(40) NULL AFTER default_mode,
    ADD COLUMN email VARCHAR(180) NULL AFTER phone,
    ADD COLUMN website VARCHAR(180) NULL AFTER email;

-- Indice por codigo interno para busquedas rapidas por la asesoria
-- (sin UNIQUE — dos clientes podrian tener el mismo codigo por error
-- y queremos avisar en UI, no bloquear con SQL).
CREATE INDEX ix_customers_internal_code ON customers (company_id, internal_code);
