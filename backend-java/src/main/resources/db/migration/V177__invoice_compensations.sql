-- COMP- (Tema 1) — Compensación (netting) de facturas: saldar una venta (430)
-- contra una compra (400) del mismo tercero (mismo NIF), extinguiendo ambas
-- hasta la cantidad concurrente (CC arts. 1195-1202). Detalle en
-- docs/compensacion-legal.md.
--
-- Estas tablas REGISTRAN cada compensación ejecutada (para el justificante
-- COMP-4 y la reversión COMP-5). El asiento contable (Debe 400 / Haber 430)
-- vive en journal_entries con source_type='COMPENSATION' + source_id = el id
-- de aquí. El saldado de cada factura se hace por invoice_due_dates PAID
-- enlazados a ese asiento (sin asiento de tesorería, es un medio de pago).
--
-- OJO nomenclatura: "compensación" ya se usa para el IVA
-- (vat_compensation_baseline, casilla 110). Esto es netting de facturas: otra
-- cosa. Additive: no toca seeds ni tablas existentes.
--
-- COLLATE utf8mb4_unicode_ci explícito en las FK (gotcha MariaDB 11.4: el
-- default distinto rompe con errno 150 y el backend no arranca).

CREATE TABLE invoice_compensations (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    counterparty_nif VARCHAR(40) NOT NULL,
    counterparty_name VARCHAR(200) NULL,
    compensation_date DATE NOT NULL,
    amount DECIMAL(14,2) NOT NULL,                 -- importe compensado (concurrente)
    journal_entry_id CHAR(36) NULL,                -- asiento 400/430 generado
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | REVERSED
    created_by CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reversed_at TIMESTAMP NULL,
    CONSTRAINT pk_invoice_compensations PRIMARY KEY (id),
    CONSTRAINT fk_icomp_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_icomp_status CHECK (status IN ('ACTIVE', 'REVERSED')),
    INDEX ix_icomp_company (company_id, status),
    INDEX ix_icomp_nif (company_id, counterparty_nif)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE invoice_compensation_lines (
    id CHAR(36) NOT NULL,
    compensation_id CHAR(36) NOT NULL,
    invoice_kind VARCHAR(10) NOT NULL,             -- SALES | PURCHASE
    invoice_id CHAR(36) NOT NULL,
    invoice_number VARCHAR(80) NULL,               -- copia para el justificante
    amount DECIMAL(14,2) NOT NULL,                 -- importe compensado de esa factura
    CONSTRAINT pk_invoice_compensation_lines PRIMARY KEY (id),
    CONSTRAINT fk_icompl_parent FOREIGN KEY (compensation_id)
        REFERENCES invoice_compensations (id),
    CONSTRAINT ck_icompl_kind CHECK (invoice_kind IN ('SALES', 'PURCHASE')),
    INDEX ix_icompl_parent (compensation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
