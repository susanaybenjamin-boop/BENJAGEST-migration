-- ===========================================================================
-- V50__recurring_and_external_import.sql
--
-- Dos bloques:
--   1) Tareas recurrentes (gastos, ventas, asientos, plantillas, cuotas
--      préstamos) — se disparan desde RecurringTaskScheduler vía @Scheduled
--      cron diario.
--   2) Batches de importación desde otros programas (Contasol, A3, Sage,
--      CSV genérico). Solo cabecera del batch — el detalle lo aporta el
--      target table (accounting_accounts, journal_entries, etc.).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS recurring_tasks (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    name VARCHAR(180) NOT NULL,
    description TEXT NULL,
    frequency VARCHAR(20) NOT NULL,
    day_of_month INT NULL,
    day_of_week INT NULL,
    months_between INT NOT NULL DEFAULT 1,
    next_run_date DATE NOT NULL,
    last_run_date DATE NULL,
    last_run_status VARCHAR(20) NULL,
    last_run_message TEXT NULL,
    last_generated_id CHAR(36) NULL,
    times_run INT NOT NULL DEFAULT 0,
    times_failed INT NOT NULL DEFAULT 0,
    end_date DATE NULL,
    payload_json TEXT NOT NULL,
    template_id CHAR(36) NULL,
    loan_id CHAR(36) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_recurring_tasks PRIMARY KEY (id),
    CONSTRAINT fk_rt_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_rt_template FOREIGN KEY (template_id) REFERENCES accounting_entry_templates (id),
    CONSTRAINT fk_rt_loan FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT fk_rt_user FOREIGN KEY (created_by_user_id) REFERENCES user_accounts (id),
    CONSTRAINT ck_rt_kind CHECK (kind IN (
        'PURCHASE', 'SALES_INVOICE', 'JOURNAL_ENTRY',
        'TEMPLATE_APPLY', 'LOAN_AUTO_PAY'
    )),
    CONSTRAINT ck_rt_frequency CHECK (frequency IN (
        'DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY', 'CUSTOM_MONTHS'
    )),
    CONSTRAINT ck_rt_last_status CHECK (last_run_status IS NULL OR
        last_run_status IN ('OK', 'SKIPPED', 'ERROR')),
    INDEX ix_rt_company_due (company_id, active, next_run_date),
    INDEX ix_rt_global_due (active, next_run_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Histórico de ejecuciones — útil para auditar y depurar.
CREATE TABLE IF NOT EXISTS recurring_task_runs (
    id CHAR(36) NOT NULL,
    recurring_task_id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    run_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    scheduled_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    generated_id CHAR(36) NULL,
    generated_kind VARCHAR(40) NULL,
    message TEXT NULL,
    duration_ms INT NULL,
    CONSTRAINT pk_recurring_task_runs PRIMARY KEY (id),
    CONSTRAINT fk_rtr_task FOREIGN KEY (recurring_task_id) REFERENCES recurring_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_rtr_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_rtr_status CHECK (status IN ('OK', 'SKIPPED', 'ERROR')),
    INDEX ix_rtr_task_time (recurring_task_id, run_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================================================
-- Importación desde otros programas de gestión
-- ===========================================================================

CREATE TABLE IF NOT EXISTS external_import_batches (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    source_format VARCHAR(20) NOT NULL,
    target_kind VARCHAR(40) NOT NULL,
    file_name VARCHAR(240) NULL,
    file_size_bytes INT NULL,
    rows_total INT NOT NULL DEFAULT 0,
    rows_imported INT NOT NULL DEFAULT 0,
    rows_skipped INT NOT NULL DEFAULT 0,
    rows_errors INT NOT NULL DEFAULT 0,
    error_log TEXT NULL,
    imported_by_user_id CHAR(36) NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes TEXT NULL,
    CONSTRAINT pk_external_import_batches PRIMARY KEY (id),
    CONSTRAINT fk_eib_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_eib_user FOREIGN KEY (imported_by_user_id) REFERENCES user_accounts (id),
    CONSTRAINT ck_eib_format CHECK (source_format IN (
        'CSV', 'CONTASOL', 'A3', 'SAGE', 'XML_ESPI', 'JSON_BENJAGEST'
    )),
    CONSTRAINT ck_eib_target CHECK (target_kind IN (
        'ACCOUNTS', 'JOURNAL_ENTRIES', 'INVOICES_SALES', 'INVOICES_PURCHASE',
        'CUSTOMERS', 'SUPPLIERS', 'FIXED_ASSETS', 'LOANS'
    )),
    INDEX ix_eib_company_time (company_id, imported_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
