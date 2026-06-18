-- =============================================================================
-- V129 — FM-1: Dispositivos de KIOSCO/PDA para fichaje (port CONTENDO
-- kiosk_devices_180 / kiosk_activation_tokens_180 / kiosk_empleados_180).
--
-- Decisiones Benjamin 2026-06-17 (ver docs/plan-fichaje-movil-kiosco.md):
--   - Emparejamiento de la tablet por QR (token 30 min); empleado se identifica
--     por PIN (employees.pin_hash, ya existe). Sin OTP por email.
--   - Foto OPCIONAL al fichar (no reconocimiento facial — solo evidencia, por
--     legalidad AEPD): configurable por dispositivo (require_photo) + retención.
--   - Offline: fase 2 (no en esta versión).
--
-- Los secretos (device_token, offline_pin del dispositivo) se guardan HASHEADOS,
-- igual que pin_hash. El fichaje en sí se sigue creando con TimeClockService
-- sobre time_clock_events (no se duplica la cadena hash ni la validación geo).
-- =============================================================================

CREATE TABLE IF NOT EXISTS kiosk_devices (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    work_center_id CHAR(36) NULL,
    name VARCHAR(120) NOT NULL,
    -- Secreto persistente del dispositivo (header KioskToken). Solo el hash.
    device_token_hash VARCHAR(255) NOT NULL,
    -- PIN offline del dispositivo (bcrypt). Opcional. Para fase 2 offline.
    offline_pin_hash VARCHAR(255) NULL,
    -- FM: foto-evidencia opcional al fichar (NO reconocimiento facial).
    require_photo BOOLEAN NOT NULL DEFAULT FALSE,
    photo_retention_days INT NOT NULL DEFAULT 90,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_kiosk_devices PRIMARY KEY (id),
    CONSTRAINT fk_kiosk_devices_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_kiosk_devices_wc FOREIGN KEY (work_center_id) REFERENCES work_centers (id) ON DELETE SET NULL,
    INDEX ix_kiosk_devices_company (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Token QR de activación: la tablet lo canjea por su KioskToken. Caduca a 30 min.
CREATE TABLE IF NOT EXISTS kiosk_activation_tokens (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    kiosk_device_id CHAR(36) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_kiosk_activation_tokens PRIMARY KEY (id),
    CONSTRAINT fk_kiosk_act_device FOREIGN KEY (kiosk_device_id) REFERENCES kiosk_devices (id) ON DELETE CASCADE,
    INDEX ix_kiosk_act_company (company_id),
    INDEX ix_kiosk_act_device (kiosk_device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Empleados habilitados en cada kiosco (qué empleados aparecen para identificarse).
CREATE TABLE IF NOT EXISTS kiosk_employee_assignments (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    kiosk_device_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_kiosk_emp PRIMARY KEY (id),
    CONSTRAINT fk_kiosk_emp_device FOREIGN KEY (kiosk_device_id) REFERENCES kiosk_devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_kiosk_emp_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    CONSTRAINT uk_kiosk_emp UNIQUE (kiosk_device_id, employee_id),
    INDEX ix_kiosk_emp_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
