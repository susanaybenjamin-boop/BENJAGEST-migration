-- ===========================================================================
-- V34__fixed_assets_and_fiscal_year_close.sql
--
-- Cierra dos bloques ALTA:
--
--   1) Inmovilizado y amortizaciones (RD 1514/2007 grupo 2):
--      - fixed_assets: bienes que la empresa adquiere para uso prolongado
--        (edificios, vehículos, equipos, software). Cada uno tiene un
--        método de amortización (lineal hoy; degresivos en sub-slice).
--      - fixed_asset_depreciations: línea por mes/año de cuota
--        amortizada. Lleva amortización acumulada y valor neto contable.
--
--   2) Cierre de ejercicio:
--      - fiscal_year_closes: estado del cierre por año. Lleva fecha de
--        cierre, resultado del ejercicio (beneficio/pérdida), aplicación
--        (reservas/dividendos), saldo de la cuenta 129 al cierre.
--      - V2 ya tenía `fiscal_years` (estado open/closed), aquí
--        complementamos con los datos del cierre real.
--
-- Decisiones honestas:
--   - El cálculo de amortización lo hace el servicio en código (Java
--     hace el redondeo HALF_UP). La tabla solo persiste el resultado.
--   - El cierre NO hace los asientos contables — eso requiere el módulo
--     contable real (libro diario). Aquí se persiste la decisión y los
--     totales; los asientos automáticos vienen en sub-slice futuro.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fixed_assets (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT NULL,
    category VARCHAR(40) NOT NULL,
    accounting_account_id CHAR(36) NULL,
    acquisition_date DATE NOT NULL,
    acquisition_cost DECIMAL(14,2) NOT NULL,
    residual_value DECIMAL(14,2) NOT NULL DEFAULT 0,
    useful_life_years DECIMAL(5,2) NOT NULL,
    depreciation_method VARCHAR(20) NOT NULL DEFAULT 'LINEAR',
    in_service_date DATE NULL,
    disposed_at DATE NULL,
    disposal_reason VARCHAR(120) NULL,
    disposal_value DECIMAL(14,2) NULL,
    supplier_name VARCHAR(200) NULL,
    invoice_reference VARCHAR(120) NULL,
    notes TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_fixed_assets PRIMARY KEY (id),
    CONSTRAINT uk_fixed_assets_code UNIQUE (company_id, code),
    CONSTRAINT fk_fixed_assets_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_fixed_assets_account FOREIGN KEY (accounting_account_id) REFERENCES accounting_accounts (id),
    CONSTRAINT ck_fixed_assets_method CHECK (depreciation_method IN ('LINEAR', 'DEGRESSIVE', 'NONE')),
    CONSTRAINT ck_fixed_assets_category CHECK (category IN (
        'BUILDING', 'LAND', 'MACHINERY', 'VEHICLE', 'IT_EQUIPMENT',
        'OFFICE_FURNITURE', 'SOFTWARE', 'INTANGIBLE', 'OTHER'
    )),
    INDEX ix_fixed_assets_company_active (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fixed_asset_depreciations (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    asset_id CHAR(36) NOT NULL,
    period_year INT NOT NULL,
    period_month INT NULL,
    depreciation_amount DECIMAL(14,2) NOT NULL,
    accumulated_amount DECIMAL(14,2) NOT NULL,
    net_book_value DECIMAL(14,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CALCULATED',
    journal_entry_id CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_fixed_asset_depreciations PRIMARY KEY (id),
    CONSTRAINT uk_fixed_asset_depreciations_period UNIQUE (asset_id, period_year, period_month),
    CONSTRAINT fk_fixed_asset_depreciations_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_fixed_asset_depreciations_asset FOREIGN KEY (asset_id) REFERENCES fixed_assets (id),
    CONSTRAINT ck_fad_status CHECK (status IN ('CALCULATED', 'POSTED', 'CANCELLED')),
    CONSTRAINT ck_fad_month CHECK (period_month IS NULL OR period_month BETWEEN 1 AND 12),
    INDEX ix_fad_company_period (company_id, period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================================================
-- Cierre de ejercicio
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fiscal_year_closes (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    period_year INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    income_total DECIMAL(14,2) NULL,
    expense_total DECIMAL(14,2) NULL,
    result_amount DECIMAL(14,2) NULL,
    tax_amount DECIMAL(14,2) NULL,
    result_after_tax DECIMAL(14,2) NULL,
    reserves_allocation DECIMAL(14,2) NULL,
    dividends_allocation DECIMAL(14,2) NULL,
    accumulated_losses_allocation DECIMAL(14,2) NULL,
    closed_at TIMESTAMP NULL,
    closed_by CHAR(36) NULL,
    reopened_at TIMESTAMP NULL,
    reopened_by CHAR(36) NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_fiscal_year_closes PRIMARY KEY (id),
    CONSTRAINT uk_fiscal_year_closes_period UNIQUE (company_id, period_year),
    CONSTRAINT fk_fyc_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_fyc_closed_by FOREIGN KEY (closed_by) REFERENCES user_accounts (id),
    CONSTRAINT fk_fyc_reopened_by FOREIGN KEY (reopened_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_fyc_status CHECK (status IN ('OPEN', 'PRE_CLOSE', 'CLOSED', 'REOPENED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
