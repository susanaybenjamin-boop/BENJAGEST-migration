-- ===========================================================================
-- V134 — MEMP-4: solicitudes de ausencia del empleado (vacaciones / baja IT /
-- permiso retribuido / otros), pedidas desde el portal del empleado (PWA) con
-- adjuntos opcionales (obligatorio en baja: parte de baja).
--
-- Aditiva: no toca seeds ni tablas existentes. La aprobacion de una VACATION
-- espeja una fila APPROVED en employee_vacations (V114) para que el finiquito
-- siga calculando vacaciones disfrutadas sin cambios.
-- ===========================================================================

CREATE TABLE employee_leave_requests (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    -- VACATION | SICK_LEAVE | PAID_LEAVE | OTHER
    kind VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    -- Dias (para vacaciones; admite medios dias). Opcional al solicitar.
    days DECIMAL(5,1) NULL,
    reason VARCHAR(240) NULL,
    -- REQUESTED | APPROVED | REJECTED | CANCELLED
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    -- Quien revisa (user_accounts.id) + cuando + nota. Sin FK para no acoplar.
    reviewed_by CHAR(36) NULL,
    reviewed_at TIMESTAMP NULL,
    review_notes VARCHAR(240) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_employee_leave_requests PRIMARY KEY (id),
    CONSTRAINT fk_elr_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_elr_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    CONSTRAINT ck_elr_kind CHECK (kind IN ('VACATION', 'SICK_LEAVE', 'PAID_LEAVE', 'OTHER')),
    CONSTRAINT ck_elr_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    INDEX ix_elr_company_emp (company_id, employee_id),
    INDEX ix_elr_company_status (company_id, status),
    INDEX ix_elr_dates (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Adjuntos de una solicitud (1 solicitud -> N ficheros). Para on-premise
-- ("todo es un puesto") el contenido se guarda como BLOB en la propia BD,
-- evitando gestionar un sistema de ficheros. Limite de tamano en la app (~5 MB).
CREATE TABLE employee_leave_attachments (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    request_id CHAR(36) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NULL,
    size_bytes INT NOT NULL,
    content LONGBLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_employee_leave_attachments PRIMARY KEY (id),
    CONSTRAINT fk_ela_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_ela_request FOREIGN KEY (request_id)
        REFERENCES employee_leave_requests (id) ON DELETE CASCADE,
    INDEX ix_ela_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
