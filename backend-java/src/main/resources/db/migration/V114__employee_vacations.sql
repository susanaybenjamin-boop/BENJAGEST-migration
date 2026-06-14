-- ===========================================================================
-- V114 — Registro de vacaciones del empleado (bloque CICLO-VIDA / CV-VAC).
--
-- Permite saber los días de vacaciones DISFRUTADOS por empleado y año, para
-- que el finiquito calcule las vacaciones PENDIENTES de forma automática
-- (devengadas − disfrutadas). Reutilizable para solicitudes/calendario.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS employee_vacations (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    -- Días de vacaciones del periodo (laborables; admite medios días).
    days DECIMAL(5,1) NOT NULL,
    -- REQUESTED (solicitado) | APPROVED (aprobado/disfrutado) | REJECTED.
    status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    notes VARCHAR(240) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_employee_vacations PRIMARY KEY (id),
    CONSTRAINT fk_emp_vac_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_emp_vac_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    INDEX ix_emp_vac_company_emp (company_id, employee_id),
    INDEX ix_emp_vac_dates (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
