-- =============================================================================
-- V86 — Esqueleto PORT-2 (fichaje extendido) con modelo "billing
-- embebido" estilo CONTENDO. Decisión Benjamin 2026-06-10.
--
-- TABLAS:
--   - workday_templates       → "plantilla de jornada": Lunes 8h, Sab 4h
--   - workday_template_blocks → bloques horarios de cada plantilla
--                               (09:00-13:00 + 14:00-18:00)
--   - work_shifts             → asignación de plantilla a un empleado en
--                               una fecha concreta (o rango).
--   - work_logs               → "parte de día" del empleado. Tiene flag
--                               is_billable + billable_amount. Al
--                               facturar, billed_invoice_line_id apunta
--                               a la línea generada en sales_invoices.
--                               PORT-2 EMBEBIDO: el work_log es la
--                               fuente, la factura agrupa N work_logs.
--
-- LO QUE NO ENTRA EN ESTA MIGRACIÓN (queda para slices siguientes
-- cuando Benjamin diseñe la UX):
--   - plannings (asignación masiva)
--   - turnos rotativos (turno_bloques)
--   - validacion admin del parte (workflow new → submitted → approved)
--
-- Por ahora work_logs.status sigue un ciclo simple DRAFT → APPROVED →
-- BILLED. Cuando Benjamin defina la UX completa, V87+ amplía.
--
-- Modulo "shifts" registrado en module_catalog + activado en TODAS las
-- empresas. Visible solo si el empresario decide usarlo desde
-- Configuracion > Modulos (puede apagarlo si su negocio es fijo).
-- =============================================================================

CREATE TABLE IF NOT EXISTS workday_templates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(240) NULL,
    -- HOURLY | FIXED_DAILY | SHIFT_BASED — informacional para UI
    kind VARCHAR(30) NOT NULL DEFAULT 'HOURLY',
    -- precio por hora si is_billable=TRUE — usado para work_logs
    -- derivados de esta plantilla cuando no se override.
    default_hourly_rate DECIMAL(10,2) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_workday_templates PRIMARY KEY (id),
    CONSTRAINT fk_workday_templates_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_workday_templates_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workday_template_blocks (
    id CHAR(36) NOT NULL,
    template_id CHAR(36) NOT NULL,
    -- 1=Lunes ... 7=Domingo (ISO-8601). NULL = aplica a todos los dias.
    day_of_week TINYINT NULL,
    starts_at TIME NOT NULL,
    ends_at TIME NOT NULL,
    block_type VARCHAR(30) NOT NULL DEFAULT 'WORK',
    -- WORK | BREAK | LUNCH | TRAVEL
    notes VARCHAR(240) NULL,
    CONSTRAINT pk_workday_template_blocks PRIMARY KEY (id),
    CONSTRAINT fk_workday_blocks_template FOREIGN KEY (template_id)
        REFERENCES workday_templates (id) ON DELETE CASCADE,
    INDEX ix_workday_blocks_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS work_shifts (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    template_id CHAR(36) NULL,
    -- Rango de aplicacion del turno (NULL = indefinido)
    starts_on DATE NOT NULL,
    ends_on DATE NULL,
    -- Si el turno es para un cliente concreto (CONTENDO: parte facturable)
    customer_id CHAR(36) NULL,
    notes VARCHAR(240) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_shifts PRIMARY KEY (id),
    CONSTRAINT fk_work_shifts_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_work_shifts_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_work_shifts_template FOREIGN KEY (template_id) REFERENCES workday_templates (id),
    CONSTRAINT fk_work_shifts_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    INDEX ix_work_shifts_company_employee (company_id, employee_id),
    INDEX ix_work_shifts_dates (starts_on, ends_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS work_logs (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    log_date DATE NOT NULL,
    -- Minutos totales trabajados (calculados o introducidos a mano).
    minutes_worked INT NOT NULL DEFAULT 0,
    -- Cliente al que se imputa el trabajo (NULL = trabajo interno no
    -- facturable). PORT-2 EMBEBIDO: si is_billable=TRUE y customer_id
    -- NOT NULL, este log se convierte en linea de factura al cobrar.
    customer_id CHAR(36) NULL,
    -- Texto libre que ira como descripcion de la linea de factura.
    description VARCHAR(500) NULL,
    is_billable BOOLEAN NOT NULL DEFAULT FALSE,
    -- Importe a facturar al cliente. Si NULL pero is_billable=TRUE,
    -- se calcula minutes_worked / 60 * workday_templates.default_hourly_rate.
    billable_amount DECIMAL(12,2) NULL,
    -- Ciclo: DRAFT → APPROVED → BILLED (cancelable: APPROVED → DRAFT
    -- siempre que no este BILLED). En PORT-2 final se amplia con
    -- SUBMITTED para validacion admin.
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    -- Cuando este log se factura, apunta a la linea generada.
    billed_invoice_line_id CHAR(36) NULL,
    -- Validacion admin (PORT-2 final): quien aprueba y cuando.
    approved_by_user_id CHAR(36) NULL,
    approved_at TIMESTAMP NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_logs PRIMARY KEY (id),
    CONSTRAINT fk_work_logs_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_work_logs_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_work_logs_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    INDEX ix_work_logs_company_date (company_id, log_date),
    INDEX ix_work_logs_employee_date (employee_id, log_date),
    INDEX ix_work_logs_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Modulo "shifts" — visible en el sidebar para que el empresario
-- decida si lo activa. Asesoria-side: una asesoria que tambien factura
-- por horas a sus clientes lo usaria.
INSERT INTO module_catalog
       (id, slug, label, description, parent_id, icon,
        display_order, advisory_only)
VALUES (UUID(), 'shifts', 'Jornadas y partes',
        'Plantillas de jornada, turnos asignados a empleados y partes de día facturables. Modelo embebido: un parte facturable genera línea de factura cuando se cobra.',
        NULL, 'fas-business-time', 145, FALSE)
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO company_modules (id, company_id, module_id, active,
                              activated_at, activated_by)
SELECT UUID(), c.id, m.id, FALSE, NULL, NULL  -- inactivo por defecto
  FROM companies c
  CROSS JOIN (SELECT id FROM module_catalog WHERE slug = 'shifts') m
 WHERE 1 = 1
ON DUPLICATE KEY UPDATE active = active;  -- no-op si ya existe
