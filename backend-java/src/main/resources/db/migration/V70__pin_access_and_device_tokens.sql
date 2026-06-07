-- =============================================================================
-- V70 — Acceso a la app por PIN y emparejado de dispositivos.
--
-- Contexto de diseño (acordado 2026-06-07 con Benjamin):
--
--   En modo asesoría, la app vive en hasta 5 ordenadores físicos compartiendo
--   UNA "cuenta" de asesoría. Cada empleado se identifica con su PIN personal
--   (4-6 dígitos) — el PIN es la prueba de quién está delante del ordenador.
--
--   El OWNER de la asesoría es el ÚNICO que crea/cambia PINs (no hay flujo
--   self-service "olvidé mi PIN" para el empleado — se lo pide al OWNER).
--
--   Cada ordenador queda "emparejado" con la asesoría mediante un device_token
--   persistente. La primera vez se empareja con email+password del OWNER
--   (handshake humano), después arranca directamente en la pantalla de PIN.
--
-- Migración del esquema PIN existente:
--
--   V3 ya creó employees.pin_hash VARCHAR(64) con SHA256 hex determinista y
--   UK (company_id, pin_hash). SHA256 sin salt es DÉBIL para PINs cortos
--   (rompible en segundos con tablas rainbow). V70 lo migra a bcrypt:
--
--   - Amplía pin_hash a VARCHAR(255) para que quepa el formato bcrypt
--     ($2a$10$... ≈ 60 chars), dejando margen para futuros algoritmos.
--   - Borra UK (company_id, pin_hash). Bcrypt usa salt aleatorio, así que
--     dos PINs iguales producen hashes distintos: una UK por hash NO
--     garantiza unicidad de PIN. El servicio L4-2 hace verify uno-a-uno
--     contra todos los empleados de la company al guardar un PIN nuevo
--     y devuelve 409 si encuentra colisión.
--   - Marca como inválidos los hashes SHA256 existentes (rellenando con
--     NULL): si V3 había sembrado PINs demo, hay que reasignarlos con
--     bcrypt. La asesoría real de Benjamin recibirá su PIN en L4-8.
--
-- =============================================================================

-- 1) employees.app_access — flag explícito "este empleado USA la app".
--    Empleados sin acceso (mozo de almacén, eventual) solo viven en Personal
--    para nómina/fichaje, no aparecen en el módulo Equipo ni pueden hacer
--    login.
ALTER TABLE employees
    ADD COLUMN app_access BOOLEAN NOT NULL DEFAULT FALSE AFTER user_id;

-- 2) pin_hash: ampliar para bcrypt + eliminar UK por hash determinista.
ALTER TABLE employees
    DROP INDEX uk_employees_company_pin;

ALTER TABLE employees
    MODIFY COLUMN pin_hash VARCHAR(255) NULL;

-- 3) Anular hashes SHA256 antiguos (V3 seed demo). Quien los necesite,
--    el OWNER les asigna PIN nuevo desde la UI (L4-4 + L4-5). En BD
--    productiva sin seed demo este UPDATE no afecta a nadie.
UPDATE employees
   SET pin_hash = NULL
 WHERE pin_hash IS NOT NULL
   AND pin_hash NOT LIKE '$2a$%'
   AND pin_hash NOT LIKE '$2b$%';

-- 4) device_tokens — un token por ordenador físico, persistente en disco.
--    El handshake inicial (L4-2) acepta email+password del OWNER y devuelve
--    un secret aleatorio que la UI guarda en su config local. En arranques
--    siguientes la UI manda ese secret en el handshake → el backend resuelve
--    qué asesoría es ESE ordenador y muestra el teclado PIN.
--
--    Constraint operativo: máx 5 tokens activos (revoked_at IS NULL) por
--    company_id. Lo enforce el servicio L4-2 al emparejar el sexto, NO un
--    CHECK (MariaDB no soporta CHECK con subqueries).
CREATE TABLE device_tokens (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    -- token_hash: bcrypt del secret aleatorio. El secret en plano solo
    -- existe en disco del PC + en el momento de emparejar. La BD no lo
    -- conoce, así que un dump no compromete los dispositivos.
    token_hash VARCHAR(255) NOT NULL,
    -- token_prefix: primeros 8 chars del secret en plano, para que el
    -- OWNER identifique en la lista "Mis equipos" qué dispositivo está
    -- borrando (los hashes son indistinguibles a ojo).
    token_prefix VARCHAR(16) NOT NULL,
    name VARCHAR(120) NOT NULL,
    paired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paired_by_user_id CHAR(36) NULL,
    last_seen_at TIMESTAMP NULL,
    revoked_at TIMESTAMP NULL,
    revoked_by_user_id CHAR(36) NULL,
    CONSTRAINT pk_device_tokens PRIMARY KEY (id),
    CONSTRAINT fk_device_tokens_company FOREIGN KEY (company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_device_tokens_paired_by FOREIGN KEY (paired_by_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT fk_device_tokens_revoked_by FOREIGN KEY (revoked_by_user_id)
        REFERENCES user_accounts (id),
    INDEX ix_device_tokens_company_active (company_id, revoked_at),
    INDEX ix_device_tokens_prefix (token_prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5) Índice complementario para login PIN: busqueda por (company_id, app_access)
--    es la query caliente del PinAuthService. employees.company_id ya tiene
--    índice; añadimos uno compuesto con app_access para acelerar el filtrado.
CREATE INDEX ix_employees_company_app_access
    ON employees (company_id, app_access);
