-- JOR-2 — Planificacion de jornada (plantillas de horario).
-- Modelo CONTENDO (plantillas_jornada_180 + plantilla_dias + bloques):
-- 1 plantilla = N bloques horarios (por dia de la semana), asignable a M
-- empleados con vigencia. Decision Benjamin 2026-06-18.
-- Additive: no toca seeds ni tablas existentes.

CREATE TABLE work_schedule_templates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description TEXT NULL,
    template_type VARCHAR(20) NOT NULL DEFAULT 'WEEKLY',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_schedule_templates PRIMARY KEY (id),
    CONSTRAINT fk_wst_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_wst_company (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bloques horarios: un bloque pertenece a (plantilla, dia de la semana).
-- weekday 1=lunes .. 7=domingo (ISO). block_type WORK|BREAK.
-- Sin bloques para un weekday = dia libre.
CREATE TABLE work_schedule_blocks (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    template_id CHAR(36) NOT NULL,
    weekday TINYINT NOT NULL,
    block_type VARCHAR(10) NOT NULL DEFAULT 'WORK',
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_work_schedule_blocks PRIMARY KEY (id),
    CONSTRAINT fk_wsb_template FOREIGN KEY (template_id)
        REFERENCES work_schedule_templates (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsb_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_wsb_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_wsb_type CHECK (block_type IN ('WORK', 'BREAK')),
    INDEX ix_wsb_template (template_id, weekday, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Asignacion plantilla -> empleado con vigencia (effective_from/to).
CREATE TABLE work_schedule_assignments (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    template_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_schedule_assignments PRIMARY KEY (id),
    CONSTRAINT fk_wsa_template FOREIGN KEY (template_id)
        REFERENCES work_schedule_templates (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsa_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_wsa_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_wsa_employee (employee_id, effective_from),
    INDEX ix_wsa_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
