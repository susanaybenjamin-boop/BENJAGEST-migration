-- PAGO-PROVEEDOR (PV-1) — Vencimientos de factura (compras y ventas).
-- Decision Benjamin 2026-06-19: modelo de vencimientos completo estilo
-- A3/Sage. Cada factura puede tener N vencimientos; cada uno se salda contra
-- una cuenta de tesoreria (banco 572 / caja 570) generando el asiento
-- 400->572/570 (compra) o 572/570->430 (venta). Cubre extracto/ticket/caja.
-- Polimorfica: invoice_kind distingue PURCHASE / SALES. Sin FK a la factura
-- (apunta a dos tablas distintas); si si se valida en el servicio.
-- Additive: no toca seeds ni tablas existentes.

CREATE TABLE invoice_due_dates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    invoice_id CHAR(36) NOT NULL,
    invoice_kind VARCHAR(10) NOT NULL,            -- PURCHASE | SALES
    seq INT NOT NULL DEFAULT 1,                   -- 1,2,3... orden del vencimiento
    due_date DATE NOT NULL,
    amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',-- PENDING | PAID
    paid_date DATE NULL,
    payment_method VARCHAR(20) NULL,              -- TRANSFER|CASH|CARD|BIZUM|OTHER
    treasury_account_code VARCHAR(20) NULL,       -- 572 banco / 570 caja / ...
    journal_entry_id CHAR(36) NULL,               -- asiento de pago generado
    bank_movement_id CHAR(36) NULL,               -- si se concilio con un movimiento
    notes VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_invoice_due_dates PRIMARY KEY (id),
    CONSTRAINT fk_idd_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_idd_kind CHECK (invoice_kind IN ('PURCHASE', 'SALES')),
    CONSTRAINT ck_idd_status CHECK (status IN ('PENDING', 'PAID')),
    INDEX ix_idd_invoice (invoice_kind, invoice_id, seq),
    INDEX ix_idd_pending (company_id, invoice_kind, status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
