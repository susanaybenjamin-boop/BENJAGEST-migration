-- CLIENT-CONFIG: datos del tab "Configuración" de la ficha del cliente.
-- Pensado sobre todo para clientes SIN vínculo (sin contabilidad en BENJAGEST
-- de la que extraer datos): la asesoría mete a mano lo necesario.

-- (1) Cifras manuales del cliente sin contabilidad: ANUAL obligatorio +
--     desglose TRIMESTRAL opcional. period_quarter = 0 (anual) | 1..4 (trimestre).
--     Alimentan RETA (rendimiento), KPIs (FIN) y avisos cuando no hay diario.
CREATE TABLE IF NOT EXISTS client_manual_financials (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    period_year INT NOT NULL,
    period_quarter INT NOT NULL DEFAULT 0,   -- 0 = resumen anual; 1..4 = trimestre
    income DECIMAL(14,2) NOT NULL DEFAULT 0,
    expenses DECIMAL(14,2) NOT NULL DEFAULT 0,
    net_result DECIMAL(14,2) NULL,           -- si NULL, se asume income - expenses
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_client_manual_financials PRIMARY KEY (id),
    CONSTRAINT uk_client_manual_financials UNIQUE (company_id, period_year, period_quarter),
    CONSTRAINT fk_client_manual_financials_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_client_manual_financials_quarter CHECK (period_quarter BETWEEN 0 AND 4),
    INDEX ix_client_manual_financials_company (company_id, period_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- (2) Config interna del cliente gestionada por la asesoría: periodicidad de
--     modelos, vía de contacto y notas internas (no visibles para el cliente).
CREATE TABLE IF NOT EXISTS client_advisory_config (
    company_id CHAR(36) NOT NULL,
    fiscal_period VARCHAR(20) NULL,          -- MONTHLY | QUARTERLY (periodicidad modelos)
    tax_regime VARCHAR(40) NULL,             -- p.ej. ESTIMACION_DIRECTA / MODULOS / SOCIEDADES
    contact_channel VARCHAR(30) NULL,        -- EMAIL | PHONE | WHATSAPP | IN_PERSON | OTHER
    contact_value VARCHAR(180) NULL,
    internal_notes TEXT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_client_advisory_config PRIMARY KEY (company_id),
    CONSTRAINT fk_client_advisory_config_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
