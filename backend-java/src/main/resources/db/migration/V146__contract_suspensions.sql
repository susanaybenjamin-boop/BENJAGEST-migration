-- ===========================================================================
-- V146 — Periodos de SUSPENSIÓN / EXCEDENCIA del contrato (art. 45–48 ET).
--
-- Bloque CICLO-LABORAL / CL-1 (Benjamin 2026-06-25). Durante la suspensión del
-- contrato se exoneran las obligaciones de trabajar y remunerar (art. 45.2 ET):
-- no hay devengo ni nómina ordinaria. Esta tabla registra esos periodos para
-- que la nómina NO genere un recibo incorrecto a quien está en excedencia.
--
-- NO incluye IT/maternidad/paternidad (esas van por `medical_leaves`, con
-- prestación y cotización propia). Aquí van las suspensiones SIN remuneración:
-- excedencias (voluntaria/forzosa/cuidado) y suspensión de empleo y sueldo.
--
-- end_date NULL = suspensión abierta (hasta que se cierre con el reingreso).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS contract_suspensions (
    id VARCHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    contract_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(36) NOT NULL,
    type VARCHAR(40) NOT NULL,          -- EXCEDENCIA_VOLUNTARIA | EXCEDENCIA_FORZOSA |
                                        -- EXCEDENCIA_CUIDADO | SUSPENSION_EMPLEO_SUELDO | OTRA
    start_date DATE NOT NULL,
    end_date DATE NULL,                 -- NULL = abierta
    reserva_puesto BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(300) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_suspensions PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_contract_suspensions_emp
    ON contract_suspensions (company_id, employee_id, start_date);
