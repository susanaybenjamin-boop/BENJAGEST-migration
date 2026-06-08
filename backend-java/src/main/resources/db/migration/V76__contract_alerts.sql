-- =============================================================================
-- V76 — CTR-6 alertas de vencimiento de contratos.
--
-- Una tabla in-app de avisos que el motor recurrente alimenta a diario
-- mirando los empleados/contratos del tenant. Cuatro tipos:
--
--   1) PROBATION_ENDING        — fin de periodo de prueba en 7 días.
--   2) CONTRACT_ENDING_30D     — temporal/obra fin a 30 días vista.
--   3) CONTRACT_ENDING_60D     — temporal/obra fin a 60 días vista
--                                (importante para preavisar al SEPE).
--   4) BIRTHDAY                — cumpleaños del empleado (gesto humano).
--
-- Las alertas son inmutables tras crearse; el OWNER las marca como
-- "vistas" (seen_at) o "descartadas" (dismissed_at). El motor NO
-- regenera alertas ya creadas para la misma combinación
-- (company, contract, kind, fires_at) — UK lo garantiza.
-- =============================================================================

CREATE TABLE contract_alerts (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    employee_id CHAR(36) NULL,
    contract_id CHAR(36) NULL,
    kind VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(500) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    fires_at DATE NOT NULL,
    seen_at TIMESTAMP NULL,
    dismissed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_alerts PRIMARY KEY (id),
    CONSTRAINT fk_calerts_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE,
    CONSTRAINT fk_calerts_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    CONSTRAINT fk_calerts_contract FOREIGN KEY (contract_id) REFERENCES employment_contracts (id) ON DELETE CASCADE,
    CONSTRAINT ck_calerts_kind CHECK (kind IN (
        'PROBATION_ENDING', 'CONTRACT_ENDING_30D', 'CONTRACT_ENDING_60D', 'BIRTHDAY'
    )),
    CONSTRAINT ck_calerts_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT uk_calerts_dedupe UNIQUE (company_id, contract_id, employee_id, kind, fires_at),
    INDEX ix_calerts_company_pending (company_id, dismissed_at, fires_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
