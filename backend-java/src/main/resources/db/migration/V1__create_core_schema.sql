CREATE TABLE customers (
    id CHAR(36) NOT NULL,
    legal_name VARCHAR(180) NOT NULL,
    trade_name VARCHAR(180) NULL,
    tax_identifier VARCHAR(32) NOT NULL,
    customer_type VARCHAR(30) NOT NULL DEFAULT 'COMPANY',
    notes TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uk_customers_tax_identifier UNIQUE (tax_identifier),
    CONSTRAINT ck_customers_type CHECK (customer_type IN ('COMPANY', 'SELF_EMPLOYED', 'PUBLIC_ENTITY', 'OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_contacts (
    id CHAR(36) NOT NULL,
    customer_id CHAR(36) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    role_name VARCHAR(80) NULL,
    email VARCHAR(180) NULL,
    phone VARCHAR(40) NULL,
    primary_contact BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_customer_contacts PRIMARY KEY (id),
    CONSTRAINT fk_customer_contacts_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    INDEX ix_customer_contacts_customer (customer_id),
    INDEX ix_customer_contacts_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_addresses (
    id CHAR(36) NOT NULL,
    customer_id CHAR(36) NOT NULL,
    address_type VARCHAR(30) NOT NULL DEFAULT 'MAIN',
    line1 VARCHAR(180) NOT NULL,
    line2 VARCHAR(180) NULL,
    city VARCHAR(100) NOT NULL,
    province VARCHAR(100) NULL,
    postal_code VARCHAR(20) NULL,
    country VARCHAR(80) NOT NULL DEFAULT 'Espana',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_customer_addresses PRIMARY KEY (id),
    CONSTRAINT fk_customer_addresses_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_customer_addresses_type CHECK (address_type IN ('MAIN', 'BILLING', 'WORKSITE', 'OTHER')),
    INDEX ix_customer_addresses_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
