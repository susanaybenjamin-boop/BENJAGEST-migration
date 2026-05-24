CREATE TABLE companies (
    id CHAR(36) NOT NULL,
    legal_name VARCHAR(180) NOT NULL,
    trade_name VARCHAR(180) NULL,
    tax_identifier VARCHAR(32) NULL,
    company_type VARCHAR(30) NOT NULL DEFAULT 'CLIENT',
    parent_company_id CHAR(36) NULL,
    email VARCHAR(180) NULL,
    phone VARCHAR(40) NULL,
    website VARCHAR(180) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_companies PRIMARY KEY (id),
    CONSTRAINT fk_companies_parent FOREIGN KEY (parent_company_id) REFERENCES companies (id),
    CONSTRAINT uk_companies_tax_identifier UNIQUE (tax_identifier),
    CONSTRAINT ck_companies_type CHECK (company_type IN ('ADVISORY', 'CLIENT', 'MANAGED_CLIENT', 'INTERNAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_accounts (
    id CHAR(36) NOT NULL,
    email VARCHAR(180) NOT NULL,
    password_hash VARCHAR(255) NULL,
    display_name VARCHAR(160) NOT NULL,
    global_role VARCHAR(30) NOT NULL DEFAULT 'USER',
    google_id VARCHAR(120) NULL,
    avatar_url TEXT NULL,
    forced_password_change BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_accounts PRIMARY KEY (id),
    CONSTRAINT uk_user_accounts_email UNIQUE (email),
    CONSTRAINT ck_user_accounts_role CHECK (global_role IN ('ADMIN', 'ADVISOR', 'EMPLOYEE', 'USER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE company_memberships (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    role_name VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_company_memberships PRIMARY KEY (id),
    CONSTRAINT fk_company_memberships_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_company_memberships_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT uk_company_memberships_user_company UNIQUE (company_id, user_id),
    CONSTRAINT ck_company_memberships_role CHECK (role_name IN ('OWNER', 'ADMIN', 'ADVISOR', 'ACCOUNTANT', 'EMPLOYEE', 'VIEWER')),
    INDEX ix_company_memberships_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE company_settings (
    company_id CHAR(36) NOT NULL,
    enabled_modules JSON NULL,
    dashboard_widgets JSON NULL,
    mobile_modules JSON NULL,
    security_settings JSON NULL,
    ai_tokens INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_company_settings PRIMARY KEY (company_id),
    CONSTRAINT fk_company_settings_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE issuers (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    legal_name VARCHAR(180) NOT NULL,
    tax_identifier VARCHAR(32) NOT NULL,
    address_line VARCHAR(220) NULL,
    city VARCHAR(100) NULL,
    province VARCHAR(100) NULL,
    postal_code VARCHAR(20) NULL,
    country VARCHAR(80) NOT NULL DEFAULT 'Espana',
    email VARCHAR(180) NULL,
    phone VARCHAR(40) NULL,
    iban VARCHAR(34) NULL,
    registry_information TEXT NULL,
    legal_terms TEXT NULL,
    invoice_footer TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_issuers PRIMARY KEY (id),
    CONSTRAINT fk_issuers_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT uk_issuers_company_tax UNIQUE (company_id, tax_identifier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE customers
    ADD COLUMN company_id CHAR(36) NULL AFTER id,
    ADD COLUMN legacy_source VARCHAR(80) NULL AFTER company_id,
    ADD COLUMN legacy_id VARCHAR(80) NULL AFTER legacy_source;

ALTER TABLE customers
    ADD CONSTRAINT fk_customers_company FOREIGN KEY (company_id) REFERENCES companies (id);

CREATE INDEX ix_customers_company ON customers (company_id);
CREATE INDEX ix_customers_legacy ON customers (legacy_source, legacy_id);

CREATE TABLE customer_billing_profiles (
    id CHAR(36) NOT NULL,
    customer_id CHAR(36) NOT NULL,
    fiscal_name VARCHAR(180) NOT NULL,
    tax_identifier VARCHAR(32) NOT NULL,
    fiscal_type VARCHAR(40) NULL,
    billing_email VARCHAR(180) NULL,
    billing_phone VARCHAR(40) NULL,
    default_vat_percent DECIMAL(5,2) NULL,
    default_retention_percent DECIMAL(5,2) NULL,
    vat_exempt BOOLEAN NOT NULL DEFAULT FALSE,
    payment_method VARCHAR(40) NULL,
    iban VARCHAR(34) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_customer_billing_profiles PRIMARY KEY (id),
    CONSTRAINT fk_customer_billing_profiles_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    INDEX ix_customer_billing_profiles_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_items (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NULL,
    item_type VARCHAR(40) NOT NULL DEFAULT 'SERVICE',
    name VARCHAR(180) NOT NULL,
    description TEXT NULL,
    category VARCHAR(80) NULL,
    unit_price DECIMAL(14,4) NULL,
    default_vat_percent DECIMAL(5,2) NULL,
    billable BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_catalog_items PRIMARY KEY (id),
    CONSTRAINT fk_catalog_items_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_catalog_items_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_catalog_items_type CHECK (item_type IN ('SERVICE', 'PRODUCT', 'WORK', 'FEE', 'OTHER')),
    INDEX ix_catalog_items_company (company_id),
    INDEX ix_catalog_items_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_tariffs (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NOT NULL,
    catalog_item_id CHAR(36) NULL,
    tariff_type VARCHAR(40) NOT NULL DEFAULT 'FIXED',
    price DECIMAL(14,4) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_customer_tariffs PRIMARY KEY (id),
    CONSTRAINT fk_customer_tariffs_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_customer_tariffs_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_customer_tariffs_item FOREIGN KEY (catalog_item_id) REFERENCES catalog_items (id),
    CONSTRAINT ck_customer_tariffs_type CHECK (tariff_type IN ('HOURLY', 'DAILY', 'MONTHLY', 'FIXED', 'PER_WORK')),
    INDEX ix_customer_tariffs_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE invoice_series (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    invoice_kind VARCHAR(30) NOT NULL DEFAULT 'STANDARD',
    numbering_type VARCHAR(30) NOT NULL DEFAULT 'BY_YEAR',
    format_template VARCHAR(120) NULL,
    next_number INT NOT NULL DEFAULT 1,
    current_year INT NULL,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_invoice_series PRIMARY KEY (id),
    CONSTRAINT fk_invoice_series_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT uk_invoice_series_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_invoice_series_kind CHECK (invoice_kind IN ('STANDARD', 'PROFORMA', 'RECTIFYING', 'TEST')),
    CONSTRAINT ck_invoice_series_numbering CHECK (numbering_type IN ('STANDARD', 'BY_YEAR', 'PREFIXED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sales_invoices (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    issuer_id CHAR(36) NULL,
    customer_id CHAR(36) NOT NULL,
    series_id CHAR(36) NULL,
    invoice_number VARCHAR(60) NULL,
    invoice_date DATE NOT NULL,
    due_date DATE NULL,
    invoice_type VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    payment_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    subtotal DECIMAL(14,2) NOT NULL DEFAULT 0,
    vat_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    retention_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    total DECIMAL(14,2) NOT NULL DEFAULT 0,
    paid_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'EUR',
    original_invoice_id CHAR(36) NULL,
    rectifying_invoice_id CHAR(36) NULL,
    pdf_path TEXT NULL,
    notes TEXT NULL,
    validated_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_sales_invoices PRIMARY KEY (id),
    CONSTRAINT fk_sales_invoices_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_sales_invoices_issuer FOREIGN KEY (issuer_id) REFERENCES issuers (id),
    CONSTRAINT fk_sales_invoices_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_sales_invoices_series FOREIGN KEY (series_id) REFERENCES invoice_series (id),
    CONSTRAINT fk_sales_invoices_original FOREIGN KEY (original_invoice_id) REFERENCES sales_invoices (id),
    CONSTRAINT fk_sales_invoices_rectifying FOREIGN KEY (rectifying_invoice_id) REFERENCES sales_invoices (id),
    CONSTRAINT uk_sales_invoices_company_number UNIQUE (company_id, invoice_number),
    CONSTRAINT ck_sales_invoices_type CHECK (invoice_type IN ('NORMAL', 'PROFORMA', 'RECTIFYING', 'SIMPLIFIED', 'TEST')),
    CONSTRAINT ck_sales_invoices_status CHECK (status IN ('DRAFT', 'VALIDATED', 'CANCELLED', 'VOIDED')),
    CONSTRAINT ck_sales_invoices_payment CHECK (payment_status IN ('PENDING', 'PARTIAL', 'PAID', 'OVERDUE')),
    INDEX ix_sales_invoices_company_date (company_id, invoice_date),
    INDEX ix_sales_invoices_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sales_invoice_lines (
    id CHAR(36) NOT NULL,
    invoice_id CHAR(36) NOT NULL,
    catalog_item_id CHAR(36) NULL,
    description TEXT NOT NULL,
    quantity DECIMAL(14,4) NOT NULL DEFAULT 1,
    unit_price DECIMAL(14,4) NOT NULL DEFAULT 0,
    vat_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    retention_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    line_subtotal DECIMAL(14,2) NOT NULL DEFAULT 0,
    line_vat DECIMAL(14,2) NOT NULL DEFAULT 0,
    line_retention DECIMAL(14,2) NOT NULL DEFAULT 0,
    line_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sales_invoice_lines PRIMARY KEY (id),
    CONSTRAINT fk_sales_invoice_lines_invoice FOREIGN KEY (invoice_id) REFERENCES sales_invoices (id),
    CONSTRAINT fk_sales_invoice_lines_item FOREIGN KEY (catalog_item_id) REFERENCES catalog_items (id),
    INDEX ix_sales_invoice_lines_invoice (invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sales_invoice_payments (
    id CHAR(36) NOT NULL,
    invoice_id CHAR(36) NOT NULL,
    payment_date DATE NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    payment_method VARCHAR(40) NULL,
    reference VARCHAR(120) NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sales_invoice_payments PRIMARY KEY (id),
    CONSTRAINT fk_sales_invoice_payments_invoice FOREIGN KEY (invoice_id) REFERENCES sales_invoices (id),
    INDEX ix_sales_invoice_payments_invoice (invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE recurring_invoices (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NOT NULL,
    name VARCHAR(180) NOT NULL,
    frequency VARCHAR(30) NOT NULL,
    next_run_date DATE NOT NULL,
    last_run_date DATE NULL,
    template_data JSON NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_recurring_invoices PRIMARY KEY (id),
    CONSTRAINT fk_recurring_invoices_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_recurring_invoices_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_recurring_invoices_frequency CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'YEARLY', 'CUSTOM')),
    INDEX ix_recurring_invoices_next_run (company_id, next_run_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE verifactu_records (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    invoice_id CHAR(36) NOT NULL,
    mode VARCHAR(30) NOT NULL DEFAULT 'PRODUCTION',
    invoice_number VARCHAR(60) NOT NULL,
    invoice_date DATE NOT NULL,
    invoice_total DECIMAL(14,2) NOT NULL,
    current_hash VARCHAR(300) NOT NULL,
    previous_hash VARCHAR(300) NULL,
    signed_xml LONGTEXT NULL,
    qr_url TEXT NULL,
    submission_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT NULL,
    registered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP NULL,
    CONSTRAINT pk_verifactu_records PRIMARY KEY (id),
    CONSTRAINT fk_verifactu_records_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_verifactu_records_invoice FOREIGN KEY (invoice_id) REFERENCES sales_invoices (id),
    CONSTRAINT uk_verifactu_records_invoice UNIQUE (invoice_id),
    CONSTRAINT ck_verifactu_records_mode CHECK (mode IN ('TEST', 'PRODUCTION')),
    INDEX ix_verifactu_records_company_status (company_id, submission_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE verifactu_events (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    record_id CHAR(36) NULL,
    event_type VARCHAR(60) NOT NULL,
    result VARCHAR(30) NOT NULL,
    payload JSON NULL,
    detail TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_verifactu_events PRIMARY KEY (id),
    CONSTRAINT fk_verifactu_events_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_verifactu_events_record FOREIGN KEY (record_id) REFERENCES verifactu_records (id),
    INDEX ix_verifactu_events_company_date (company_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sii_configurations (
    company_id CHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    environment VARCHAR(30) NOT NULL DEFAULT 'TEST',
    certificate_id CHAR(36) NULL,
    settings JSON NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_sii_configurations PRIMARY KEY (company_id),
    CONSTRAINT fk_sii_configurations_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_sii_configurations_environment CHECK (environment IN ('TEST', 'PRODUCTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sii_submissions (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    invoice_id CHAR(36) NULL,
    submission_type VARCHAR(40) NOT NULL,
    period_year INT NOT NULL,
    period_month INT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    request_payload JSON NULL,
    response_payload JSON NULL,
    error_detail TEXT NULL,
    sent_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sii_submissions PRIMARY KEY (id),
    CONSTRAINT fk_sii_submissions_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_sii_submissions_invoice FOREIGN KEY (invoice_id) REFERENCES sales_invoices (id),
    INDEX ix_sii_submissions_company_period (company_id, period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE suppliers (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    legal_name VARCHAR(180) NOT NULL,
    tax_identifier VARCHAR(32) NULL,
    email VARCHAR(180) NULL,
    phone VARCHAR(40) NULL,
    address_line VARCHAR(220) NULL,
    city VARCHAR(100) NULL,
    province VARCHAR(100) NULL,
    postal_code VARCHAR(20) NULL,
    country VARCHAR(80) NOT NULL DEFAULT 'Espana',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_suppliers PRIMARY KEY (id),
    CONSTRAINT fk_suppliers_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT uk_suppliers_company_tax UNIQUE (company_id, tax_identifier),
    INDEX ix_suppliers_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE purchase_invoices (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    supplier_id CHAR(36) NULL,
    supplier_name VARCHAR(180) NULL,
    invoice_number VARCHAR(80) NULL,
    invoice_date DATE NOT NULL,
    category VARCHAR(80) NULL,
    subtotal DECIMAL(14,2) NOT NULL DEFAULT 0,
    vat_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    retention_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    total DECIMAL(14,2) NOT NULL DEFAULT 0,
    payment_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    document_hash VARCHAR(128) NULL,
    document_file_id CHAR(36) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_purchase_invoices PRIMARY KEY (id),
    CONSTRAINT fk_purchase_invoices_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_purchase_invoices_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    INDEX ix_purchase_invoices_company_date (company_id, invoice_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE purchase_invoice_lines (
    id CHAR(36) NOT NULL,
    purchase_invoice_id CHAR(36) NOT NULL,
    description TEXT NOT NULL,
    quantity DECIMAL(14,4) NOT NULL DEFAULT 1,
    unit_price DECIMAL(14,4) NOT NULL DEFAULT 0,
    vat_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    line_subtotal DECIMAL(14,2) NOT NULL DEFAULT 0,
    line_vat DECIMAL(14,2) NOT NULL DEFAULT 0,
    line_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_purchase_invoice_lines PRIMARY KEY (id),
    CONSTRAINT fk_purchase_invoice_lines_invoice FOREIGN KEY (purchase_invoice_id) REFERENCES purchase_invoices (id),
    INDEX ix_purchase_invoice_lines_invoice (purchase_invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE recurring_expenses (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    supplier_id CHAR(36) NULL,
    name VARCHAR(180) NOT NULL,
    frequency VARCHAR(30) NOT NULL,
    next_run_date DATE NOT NULL,
    template_data JSON NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_recurring_expenses PRIMARY KEY (id),
    CONSTRAINT fk_recurring_expenses_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_recurring_expenses_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    INDEX ix_recurring_expenses_next_run (company_id, next_run_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE accounting_accounts (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(180) NOT NULL,
    account_type VARCHAR(40) NOT NULL,
    parent_account_id CHAR(36) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_accounting_accounts PRIMARY KEY (id),
    CONSTRAINT fk_accounting_accounts_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_accounting_accounts_parent FOREIGN KEY (parent_account_id) REFERENCES accounting_accounts (id),
    CONSTRAINT uk_accounting_accounts_company_code UNIQUE (company_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE fiscal_years (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    year_number INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_fiscal_years PRIMARY KEY (id),
    CONSTRAINT fk_fiscal_years_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT uk_fiscal_years_company_year UNIQUE (company_id, year_number),
    CONSTRAINT ck_fiscal_years_status CHECK (status IN ('OPEN', 'LOCKED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE journal_entries (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    fiscal_year_id CHAR(36) NOT NULL,
    entry_number INT NOT NULL,
    entry_date DATE NOT NULL,
    concept VARCHAR(240) NOT NULL,
    source_type VARCHAR(40) NULL,
    source_id CHAR(36) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    created_by CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_journal_entries PRIMARY KEY (id),
    CONSTRAINT fk_journal_entries_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_journal_entries_year FOREIGN KEY (fiscal_year_id) REFERENCES fiscal_years (id),
    CONSTRAINT fk_journal_entries_user FOREIGN KEY (created_by) REFERENCES user_accounts (id),
    CONSTRAINT uk_journal_entries_company_number UNIQUE (company_id, fiscal_year_id, entry_number),
    INDEX ix_journal_entries_company_date (company_id, entry_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE journal_entry_lines (
    id CHAR(36) NOT NULL,
    journal_entry_id CHAR(36) NOT NULL,
    account_id CHAR(36) NOT NULL,
    description VARCHAR(240) NULL,
    debit DECIMAL(14,2) NOT NULL DEFAULT 0,
    credit DECIMAL(14,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_journal_entry_lines PRIMARY KEY (id),
    CONSTRAINT fk_journal_entry_lines_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT fk_journal_entry_lines_account FOREIGN KEY (account_id) REFERENCES accounting_accounts (id),
    INDEX ix_journal_entry_lines_entry (journal_entry_id),
    INDEX ix_journal_entry_lines_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE fixed_assets (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    supplier_id CHAR(36) NULL,
    purchase_invoice_id CHAR(36) NULL,
    name VARCHAR(180) NOT NULL,
    acquisition_date DATE NOT NULL,
    acquisition_value DECIMAL(14,2) NOT NULL,
    depreciation_method VARCHAR(40) NOT NULL DEFAULT 'LINEAR',
    useful_life_months INT NOT NULL,
    accumulated_depreciation DECIMAL(14,2) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_fixed_assets PRIMARY KEY (id),
    CONSTRAINT fk_fixed_assets_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_fixed_assets_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_fixed_assets_purchase FOREIGN KEY (purchase_invoice_id) REFERENCES purchase_invoices (id),
    INDEX ix_fixed_assets_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE year_closings (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    fiscal_year_id CHAR(36) NOT NULL,
    closing_type VARCHAR(40) NOT NULL,
    result_amount DECIMAL(14,2) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP NULL,
    CONSTRAINT pk_year_closings PRIMARY KEY (id),
    CONSTRAINT fk_year_closings_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_year_closings_year FOREIGN KEY (fiscal_year_id) REFERENCES fiscal_years (id),
    INDEX ix_year_closings_company_year (company_id, fiscal_year_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tax_models (
    id CHAR(36) NOT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(180) NOT NULL,
    tax_area VARCHAR(50) NOT NULL,
    periodicity VARCHAR(30) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_tax_models PRIMARY KEY (id),
    CONSTRAINT uk_tax_models_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tax_filings (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    tax_model_id CHAR(36) NOT NULL,
    period_year INT NOT NULL,
    period_code VARCHAR(10) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    amount_due DECIMAL(14,2) NULL,
    csv_code VARCHAR(120) NULL,
    submitted_at TIMESTAMP NULL,
    payload JSON NULL,
    response_payload JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_tax_filings PRIMARY KEY (id),
    CONSTRAINT fk_tax_filings_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_tax_filings_model FOREIGN KEY (tax_model_id) REFERENCES tax_models (id),
    CONSTRAINT uk_tax_filings_company_period UNIQUE (company_id, tax_model_id, period_year, period_code),
    INDEX ix_tax_filings_company_status (company_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE aeat_consultations (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    consultation_type VARCHAR(60) NOT NULL,
    period_year INT NULL,
    period_code VARCHAR(10) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    request_payload JSON NULL,
    response_payload JSON NULL,
    consulted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_aeat_consultations PRIMARY KEY (id),
    CONSTRAINT fk_aeat_consultations_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_aeat_consultations_company (company_id, consultation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE aeat_discrepancies (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    consultation_id CHAR(36) NULL,
    source_type VARCHAR(60) NOT NULL,
    source_id CHAR(36) NULL,
    severity VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    description TEXT NOT NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_aeat_discrepancies PRIMARY KEY (id),
    CONSTRAINT fk_aeat_discrepancies_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_aeat_discrepancies_consultation FOREIGN KEY (consultation_id) REFERENCES aeat_consultations (id),
    INDEX ix_aeat_discrepancies_company_status (company_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE employees (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    user_id CHAR(36) NULL,
    default_customer_id CHAR(36) NULL,
    full_name VARCHAR(160) NOT NULL,
    tax_identifier VARCHAR(32) NULL,
    email VARCHAR(180) NULL,
    phone VARCHAR(40) NULL,
    work_type VARCHAR(40) NULL,
    max_shift_minutes INT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_employees PRIMARY KEY (id),
    CONSTRAINT fk_employees_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_employees_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_employees_default_customer FOREIGN KEY (default_customer_id) REFERENCES customers (id),
    INDEX ix_employees_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE employment_contracts (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    contract_type VARCHAR(60) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NULL,
    weekly_hours DECIMAL(6,2) NULL,
    gross_salary DECIMAL(14,2) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    metadata JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_employment_contracts PRIMARY KEY (id),
    CONSTRAINT fk_employment_contracts_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_employment_contracts_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    INDEX ix_employment_contracts_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE work_items (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NULL,
    name VARCHAR(180) NOT NULL,
    description TEXT NULL,
    billing_type VARCHAR(40) NOT NULL DEFAULT 'HOURLY',
    default_minutes INT NULL,
    monetary_value DECIMAL(14,2) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_items PRIMARY KEY (id),
    CONSTRAINT fk_work_items_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_work_items_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    INDEX ix_work_items_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE work_logs (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NULL,
    work_item_id CHAR(36) NULL,
    invoice_id CHAR(36) NULL,
    work_date DATE NOT NULL,
    minutes INT NOT NULL DEFAULT 0,
    description TEXT NULL,
    details TEXT NULL,
    value_amount DECIMAL(14,2) NULL,
    payment_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_logs PRIMARY KEY (id),
    CONSTRAINT fk_work_logs_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_work_logs_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_work_logs_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_work_logs_item FOREIGN KEY (work_item_id) REFERENCES work_items (id),
    CONSTRAINT fk_work_logs_invoice FOREIGN KEY (invoice_id) REFERENCES sales_invoices (id),
    INDEX ix_work_logs_company_date (company_id, work_date),
    INDEX ix_work_logs_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE time_clock_events (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NULL,
    event_type VARCHAR(30) NOT NULL,
    event_time TIMESTAMP NOT NULL,
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    ip_address VARCHAR(80) NULL,
    device_hash VARCHAR(180) NULL,
    origin VARCHAR(40) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'VALID',
    suspicious BOOLEAN NOT NULL DEFAULT FALSE,
    suspicion_reason TEXT NULL,
    geo_metadata JSON NULL,
    validated_by CHAR(36) NULL,
    validated_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_time_clock_events PRIMARY KEY (id),
    CONSTRAINT fk_time_clock_events_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_time_clock_events_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_time_clock_events_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_time_clock_events_validator FOREIGN KEY (validated_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_time_clock_events_type CHECK (event_type IN ('IN', 'OUT', 'BREAK_START', 'BREAK_END')),
    INDEX ix_time_clock_events_employee_time (employee_id, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE daily_work_reports (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NULL,
    report_date DATE NOT NULL,
    summary TEXT NULL,
    worked_hours DECIMAL(6,2) NULL,
    amount DECIMAL(14,2) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    validated BOOLEAN NOT NULL DEFAULT FALSE,
    validated_by CHAR(36) NULL,
    validated_at TIMESTAMP NULL,
    invoiced BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_daily_work_reports PRIMARY KEY (id),
    CONSTRAINT fk_daily_work_reports_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_daily_work_reports_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_daily_work_reports_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_daily_work_reports_validator FOREIGN KEY (validated_by) REFERENCES user_accounts (id),
    INDEX ix_daily_work_reports_employee_date (employee_id, report_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE absences (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    absence_type VARCHAR(40) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason TEXT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    employee_comment TEXT NULL,
    admin_comment TEXT NULL,
    created_by CHAR(36) NULL,
    reviewed_by CHAR(36) NULL,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_absences PRIMARY KEY (id),
    CONSTRAINT fk_absences_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_absences_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_absences_created_by FOREIGN KEY (created_by) REFERENCES user_accounts (id),
    CONSTRAINT fk_absences_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES user_accounts (id),
    INDEX ix_absences_employee_dates (employee_id, start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payrolls (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    period_year INT NOT NULL,
    period_month INT NOT NULL,
    gross_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    employee_social_security DECIMAL(14,2) NOT NULL DEFAULT 0,
    employer_social_security DECIMAL(14,2) NOT NULL DEFAULT 0,
    irpf_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    net_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    document_file_id CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_payrolls PRIMARY KEY (id),
    CONSTRAINT fk_payrolls_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_payrolls_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT uk_payrolls_employee_period UNIQUE (employee_id, period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE medical_leaves (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NOT NULL,
    leave_type VARCHAR(60) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_medical_leaves PRIMARY KEY (id),
    CONSTRAINT fk_medical_leaves_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_medical_leaves_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    INDEX ix_medical_leaves_employee_dates (employee_id, start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE social_security_contributions (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NULL,
    period_year INT NOT NULL,
    period_month INT NOT NULL,
    contribution_type VARCHAR(60) NOT NULL,
    base_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    contribution_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_social_security_contributions PRIMARY KEY (id),
    CONSTRAINT fk_social_security_contributions_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_social_security_contributions_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    INDEX ix_social_security_contributions_company_period (company_id, period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE self_employed_profiles (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    person_name VARCHAR(180) NOT NULL,
    tax_identifier VARCHAR(32) NOT NULL,
    activity_code VARCHAR(40) NULL,
    contribution_base DECIMAL(14,2) NULL,
    profile_type VARCHAR(40) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_self_employed_profiles PRIMARY KEY (id),
    CONSTRAINT fk_self_employed_profiles_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_self_employed_profiles_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE self_employed_estimates (
    id CHAR(36) NOT NULL,
    profile_id CHAR(36) NOT NULL,
    period_year INT NOT NULL,
    estimated_income DECIMAL(14,2) NOT NULL DEFAULT 0,
    estimated_quota DECIMAL(14,2) NOT NULL DEFAULT 0,
    details JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_self_employed_estimates PRIMARY KEY (id),
    CONSTRAINT fk_self_employed_estimates_profile FOREIGN KEY (profile_id) REFERENCES self_employed_profiles (id),
    INDEX ix_self_employed_estimates_profile_year (profile_id, period_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE self_employed_events (
    id CHAR(36) NOT NULL,
    profile_id CHAR(36) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    event_date DATE NOT NULL,
    details JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_self_employed_events PRIMARY KEY (id),
    CONSTRAINT fk_self_employed_events_profile FOREIGN KEY (profile_id) REFERENCES self_employed_profiles (id),
    INDEX ix_self_employed_events_profile_date (profile_id, event_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE self_employed_alerts (
    id CHAR(36) NOT NULL,
    profile_id CHAR(36) NOT NULL,
    alert_type VARCHAR(60) NOT NULL,
    severity VARCHAR(30) NOT NULL DEFAULT 'INFO',
    message TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    due_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL,
    CONSTRAINT pk_self_employed_alerts PRIMARY KEY (id),
    CONSTRAINT fk_self_employed_alerts_profile FOREIGN KEY (profile_id) REFERENCES self_employed_profiles (id),
    INDEX ix_self_employed_alerts_profile_status (profile_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE digital_certificates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    alias VARCHAR(160) NOT NULL,
    certificate_type VARCHAR(40) NOT NULL,
    subject_name VARCHAR(220) NULL,
    subject_tax_identifier VARCHAR(32) NULL,
    encrypted_password TEXT NULL,
    storage_path TEXT NULL,
    certificate_data LONGTEXT NULL,
    valid_from TIMESTAMP NULL,
    valid_to TIMESTAMP NULL,
    metadata JSON NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_digital_certificates PRIMARY KEY (id),
    CONSTRAINT fk_digital_certificates_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_digital_certificates_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE sii_configurations
    ADD CONSTRAINT fk_sii_configurations_certificate FOREIGN KEY (certificate_id) REFERENCES digital_certificates (id);

CREATE TABLE document_files (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    owner_type VARCHAR(60) NOT NULL,
    owner_id CHAR(36) NULL,
    file_name VARCHAR(240) NOT NULL,
    mime_type VARCHAR(120) NULL,
    size_bytes BIGINT NULL,
    storage_path TEXT NOT NULL,
    sha256_hash VARCHAR(128) NULL,
    uploaded_by CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_document_files PRIMARY KEY (id),
    CONSTRAINT fk_document_files_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_document_files_user FOREIGN KEY (uploaded_by) REFERENCES user_accounts (id),
    INDEX ix_document_files_owner (owner_type, owner_id),
    INDEX ix_document_files_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE purchase_invoices
    ADD CONSTRAINT fk_purchase_invoices_document FOREIGN KEY (document_file_id) REFERENCES document_files (id);

ALTER TABLE payrolls
    ADD CONSTRAINT fk_payrolls_document FOREIGN KEY (document_file_id) REFERENCES document_files (id);

CREATE TABLE audit_events (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NULL,
    user_id CHAR(36) NULL,
    event_type VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NULL,
    entity_id CHAR(36) NULL,
    result VARCHAR(30) NOT NULL DEFAULT 'OK',
    ip_address VARCHAR(80) NULL,
    user_agent TEXT NULL,
    details JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_audit_events_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    INDEX ix_audit_events_company_date (company_id, created_at),
    INDEX ix_audit_events_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notifications (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    user_id CHAR(36) NULL,
    notification_type VARCHAR(60) NOT NULL,
    title VARCHAR(180) NOT NULL,
    body TEXT NULL,
    severity VARCHAR(30) NOT NULL DEFAULT 'INFO',
    status VARCHAR(30) NOT NULL DEFAULT 'UNREAD',
    due_at TIMESTAMP NULL,
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    INDEX ix_notifications_user_status (user_id, status),
    INDEX ix_notifications_company_date (company_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE calendar_integrations (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    provider VARCHAR(40) NOT NULL DEFAULT 'GOOGLE',
    account_email VARCHAR(180) NULL,
    refresh_token TEXT NULL,
    calendar_id VARCHAR(180) NULL,
    sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sync_direction VARCHAR(30) NOT NULL DEFAULT 'BIDIRECTIONAL',
    sync_settings JSON NULL,
    last_sync_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_calendar_integrations PRIMARY KEY (id),
    CONSTRAINT fk_calendar_integrations_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_calendar_integrations_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE calendar_events (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    integration_id CHAR(36) NULL,
    source_type VARCHAR(60) NULL,
    source_id CHAR(36) NULL,
    event_date DATE NOT NULL,
    starts_at TIMESTAMP NULL,
    ends_at TIMESTAMP NULL,
    title VARCHAR(180) NOT NULL,
    description TEXT NULL,
    event_type VARCHAR(50) NOT NULL DEFAULT 'GENERAL',
    external_event_id VARCHAR(180) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_calendar_events PRIMARY KEY (id),
    CONSTRAINT fk_calendar_events_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_calendar_events_integration FOREIGN KEY (integration_id) REFERENCES calendar_integrations (id),
    INDEX ix_calendar_events_company_date (company_id, event_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE calendar_sync_logs (
    id CHAR(36) NOT NULL,
    integration_id CHAR(36) NOT NULL,
    sync_type VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    events_created INT NOT NULL DEFAULT 0,
    events_updated INT NOT NULL DEFAULT 0,
    events_deleted INT NOT NULL DEFAULT 0,
    errors_count INT NOT NULL DEFAULT 0,
    summary JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_calendar_sync_logs PRIMARY KEY (id),
    CONSTRAINT fk_calendar_sync_logs_integration FOREIGN KEY (integration_id) REFERENCES calendar_integrations (id),
    INDEX ix_calendar_sync_logs_integration_date (integration_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_entries (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NULL,
    title VARCHAR(220) NOT NULL,
    category VARCHAR(80) NULL,
    content LONGTEXT NOT NULL,
    metadata JSON NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_knowledge_entries PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_entries_company FOREIGN KEY (company_id) REFERENCES companies (id),
    INDEX ix_knowledge_entries_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
