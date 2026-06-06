-- ===========================================================================
-- V49__bank_loans_templates.sql
--
-- Bloque contable de asesoría — tablas para BANK / LOANS / TEMPLATES.
-- Se carga en una sola migración para que la asesoría tenga el módulo
-- contable listo de una vez. Los servicios Java que la usan son:
--   - BankAccountService, BankMovementService, BankImportService
--   - LoanService
--   - FixedAssetEntriesService (usa tablas ya existentes V34)
--   - AccountingTemplateService
--
-- Notas de diseño:
--   - Todas las tablas multitenancy via company_id + índice.
--   - bank_movements lleva journal_entry_id para enlazar al asiento que
--     genera (cobro/pago). Idempotencia por (company_id, bank_account_id,
--     value_date, amount, external_ref).
--   - loans lleva el principal inicial, plazo, tasa, fecha primer pago.
--     loan_installments es el cuadro de amortización pre-calculado.
--   - accounting_entry_templates es la versión "horneada" de un asiento
--     recurrente: cabecera + plantilla líneas con account_code + signo.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- BANK ACCOUNTS
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bank_accounts (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    alias VARCHAR(80) NOT NULL,
    iban VARCHAR(34) NULL,
    bic VARCHAR(11) NULL,
    bank_name VARCHAR(120) NULL,
    accounting_account_id CHAR(36) NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    opening_balance DECIMAL(14,2) NOT NULL DEFAULT 0,
    opening_date DATE NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_bank_accounts PRIMARY KEY (id),
    CONSTRAINT uk_bank_accounts_iban UNIQUE (company_id, iban),
    CONSTRAINT fk_bank_accounts_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_bank_accounts_account FOREIGN KEY (accounting_account_id) REFERENCES accounting_accounts (id),
    INDEX ix_bank_accounts_company_active (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS bank_movements (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    bank_account_id CHAR(36) NOT NULL,
    operation_date DATE NOT NULL,
    value_date DATE NULL,
    description VARCHAR(240) NULL,
    counterparty_name VARCHAR(180) NULL,
    counterparty_nif VARCHAR(20) NULL,
    amount DECIMAL(14,2) NOT NULL,
    balance_after DECIMAL(14,2) NULL,
    external_ref VARCHAR(80) NULL,
    import_batch_id CHAR(36) NULL,
    journal_entry_id CHAR(36) NULL,
    linked_invoice_id CHAR(36) NULL,
    linked_invoice_kind VARCHAR(20) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UNRECONCILED',
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_bank_movements PRIMARY KEY (id),
    CONSTRAINT uk_bank_movements_dedup UNIQUE (company_id, bank_account_id, operation_date, amount, external_ref),
    CONSTRAINT fk_bank_movements_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_bank_movements_account FOREIGN KEY (bank_account_id) REFERENCES bank_accounts (id),
    CONSTRAINT fk_bank_movements_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT ck_bank_movements_status CHECK (status IN ('UNRECONCILED', 'MATCHED', 'POSTED', 'IGNORED')),
    CONSTRAINT ck_bank_movements_invoice_kind CHECK (linked_invoice_kind IS NULL
        OR linked_invoice_kind IN ('SALES', 'PURCHASE')),
    INDEX ix_bank_movements_company_date (company_id, operation_date),
    INDEX ix_bank_movements_account (bank_account_id, operation_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS bank_import_batches (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    bank_account_id CHAR(36) NOT NULL,
    source_format VARCHAR(20) NOT NULL,
    file_name VARCHAR(240) NULL,
    rows_total INT NOT NULL DEFAULT 0,
    rows_imported INT NOT NULL DEFAULT 0,
    rows_skipped INT NOT NULL DEFAULT 0,
    rows_auto_matched INT NOT NULL DEFAULT 0,
    period_from DATE NULL,
    period_to DATE NULL,
    imported_by_user_id CHAR(36) NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes TEXT NULL,
    CONSTRAINT pk_bank_import_batches PRIMARY KEY (id),
    CONSTRAINT fk_bib_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_bib_account FOREIGN KEY (bank_account_id) REFERENCES bank_accounts (id),
    CONSTRAINT ck_bib_format CHECK (source_format IN ('N43', 'CSV', 'MT940', 'CAMT053', 'MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- LOANS
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS loans (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    description VARCHAR(180) NOT NULL,
    lender_name VARCHAR(180) NULL,
    lender_nif VARCHAR(20) NULL,
    principal_amount DECIMAL(14,2) NOT NULL,
    interest_rate DECIMAL(7,4) NOT NULL,
    term_months INT NOT NULL,
    start_date DATE NOT NULL,
    first_installment_date DATE NOT NULL,
    installment_amount DECIMAL(14,2) NULL,
    method VARCHAR(20) NOT NULL DEFAULT 'FRENCH',
    long_term_account_id CHAR(36) NULL,
    short_term_account_id CHAR(36) NULL,
    interest_account_id CHAR(36) NULL,
    bank_account_id CHAR(36) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_loans PRIMARY KEY (id),
    CONSTRAINT uk_loans_code UNIQUE (company_id, code),
    CONSTRAINT fk_loans_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_loans_lt FOREIGN KEY (long_term_account_id) REFERENCES accounting_accounts (id),
    CONSTRAINT fk_loans_st FOREIGN KEY (short_term_account_id) REFERENCES accounting_accounts (id),
    CONSTRAINT fk_loans_int FOREIGN KEY (interest_account_id) REFERENCES accounting_accounts (id),
    CONSTRAINT fk_loans_bank FOREIGN KEY (bank_account_id) REFERENCES bank_accounts (id),
    CONSTRAINT ck_loans_method CHECK (method IN ('FRENCH', 'CONSTANT_PRINCIPAL', 'BULLET')),
    CONSTRAINT ck_loans_status CHECK (status IN ('ACTIVE', 'PAID_OFF', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS loan_installments (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    loan_id CHAR(36) NOT NULL,
    installment_number INT NOT NULL,
    due_date DATE NOT NULL,
    principal_amount DECIMAL(14,2) NOT NULL,
    interest_amount DECIMAL(14,2) NOT NULL,
    total_amount DECIMAL(14,2) NOT NULL,
    remaining_principal DECIMAL(14,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMP NULL,
    journal_entry_id CHAR(36) NULL,
    bank_movement_id CHAR(36) NULL,
    CONSTRAINT pk_loan_installments PRIMARY KEY (id),
    CONSTRAINT uk_loan_installments_num UNIQUE (loan_id, installment_number),
    CONSTRAINT fk_loan_installments_loan FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT fk_loan_installments_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_loan_installments_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT fk_loan_installments_bm FOREIGN KEY (bank_movement_id) REFERENCES bank_movements (id),
    CONSTRAINT ck_loan_installments_status CHECK (status IN ('PENDING', 'PAID', 'OVERDUE', 'CANCELLED')),
    INDEX ix_loan_installments_loan_status (loan_id, status),
    INDEX ix_loan_installments_due (company_id, due_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- ENTRY TEMPLATES (plantillas de asiento recurrentes)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS accounting_entry_templates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(180) NOT NULL,
    category VARCHAR(40) NOT NULL,
    default_concept VARCHAR(240) NULL,
    description TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id CHAR(36) NULL,
    times_used INT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_acc_entry_templates PRIMARY KEY (id),
    CONSTRAINT uk_acc_entry_templates_code UNIQUE (company_id, code),
    CONSTRAINT fk_aet_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_aet_category CHECK (category IN (
        'PAYROLL', 'SOCIAL_SECURITY', 'CORPORATE_TAX', 'VAT',
        'DEPRECIATION', 'LOAN', 'ACCRUAL', 'GENERAL'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS accounting_entry_template_lines (
    id CHAR(36) NOT NULL,
    template_id CHAR(36) NOT NULL,
    line_order INT NOT NULL,
    account_code VARCHAR(20) NOT NULL,
    description VARCHAR(240) NULL,
    side VARCHAR(10) NOT NULL,
    amount_kind VARCHAR(20) NOT NULL,
    fixed_amount DECIMAL(14,2) NULL,
    formula VARCHAR(240) NULL,
    variable_name VARCHAR(60) NULL,
    CONSTRAINT pk_aetl PRIMARY KEY (id),
    CONSTRAINT uk_aetl_order UNIQUE (template_id, line_order),
    CONSTRAINT fk_aetl_template FOREIGN KEY (template_id) REFERENCES accounting_entry_templates (id) ON DELETE CASCADE,
    CONSTRAINT ck_aetl_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_aetl_amount_kind CHECK (amount_kind IN ('FIXED', 'VARIABLE', 'FORMULA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
