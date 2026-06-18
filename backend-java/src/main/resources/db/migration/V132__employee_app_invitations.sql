-- MEMP-1 — Invitaciones para la PWA del empleado.
-- El admin (OWNER/ADMIN) genera una invitacion para un empleado con
-- app_access; el empleado abre el enlace en su movil, se canjea el token
-- (one-time) y su dispositivo queda emparejado (device_tokens) a SU empresa.
-- A partir de ahi entra por PIN (/api/auth/pin-login). Additive.

CREATE TABLE employee_app_invitations (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    token_hash VARCHAR(180) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_employee_app_invitations PRIMARY KEY (id),
    CONSTRAINT fk_eai_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_eai_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    INDEX ix_eai_token (token_hash),
    INDEX ix_eai_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
